package leasegate.gitgate.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import leasegate.core.LeaseEngine;
import leasegate.core.LeaseServer;
import leasegate.core.access.*;
import leasegate.gitgate.core.*;
import leasegate.sshagent.AgentServer;
import leasegate.transport.LeaseDeniedException;
import leasegate.transport.Links;
import leasegate.frame.*;
import leasegate.ui.*;

/// The gitgate tray. Unlock first: the passphrase opens the gate and its vault, and nothing starts
/// before. Then two pipes: the agent ssh signs through, and gitgate's own, where the gh proxy asks
/// for tokens. Every signature and every token goes through the approval window or a grant the
/// human made in it.
public final class GitgateApp extends Application {

    Settings settings;
    PassphraseGate gate;

    public static void main(String[] args) {
        System.setProperty("apple.awt.UIElement", "true");
        launch(args);
    }

    @Override public void init() {
        Platform.setImplicitExit(false);
        settings = SettingsStore.load();
        gate = new PassphraseGate(Places.passphrase());
    }

    @Override public void start(Stage stage) {
        if (alreadyRunning()) { System.err.println("gitgate is already running"); Platform.exit(); return; }
        GateFrame.styles(GateFrame.Style.of(settings.getWindows()), GateFrame.Style.of(settings.getResizableWindows()));
        stage.setTitle(Places.PRODUCT);
        var brand = new Brand(Places.PRODUCT, "#2e7d32", new GateIcons(GitgateApp.class, "gitgate"));
        var vault = VaultUnlock.open(gate, Places.vault(), brand);
        var secrets = new Secrets(vault);
        secrets.keys.writeAllPublic();

        var rules = new GitRules(settings);
        var engine = new LeaseEngine<GitScope>(rules, new BlanketModeApproval<>(new FxApproval<>(gate, rules, brand), gate),
            new KeyedAppStore<>(Places.keyedApps(), rules));
        engine.trustApproval(new FxTrust<>(gate, rules, brand));
        engine.onExpiring(e -> Platform.runLater(() -> ExpiryWarningWindow.show(engine, gate, brand, e)));
        engine.startSweep();

        // the git side follows settings.toml: its includeIf blocks and hooks are rewritten at every start
        try { if (GitSetup.installed(settings)) GitSetup.writeFiles(settings, GitgateMain.selfExe()); }
        catch (Exception e) { System.err.println("gitgate: could not refresh " + GitSetup.config() + ": " + e.getMessage()); }
        Thread.ofPlatform().name("gitgate-settings-watch").daemon().start(GitgateApp::watchSettings);
        var control = new ControlHandler(engine, secrets, settings, brand);
        var controlPipe = LeaseServer.open(Places.runDir(), Places.PIPE);
        var agentPipe = LeaseServer.open(Places.runDir(), Places.AGENT_PIPE);
        var agent = new AgentServer(secrets.keys::keys, new AgentPolicy(engine, secrets, settings, brand));
        Thread.ofPlatform().name("gitgate-control").daemon().start(() -> LeaseServer.serve(controlPipe, "gitgate", control::handle));
        Thread.ofPlatform().name("gitgate-agent").daemon().start(() -> agent.serve(agentPipe, "gitgate-agent"));

        Runnable changed = () -> {};
        var tray = new TrayShell<>(stage, engine, gate, brand, () -> line(secrets, engine));
        tray.addItem(TrayShell.item("Repositories and rules…", () -> RulesWindow.show(brand)))
            .addItem(TrayShell.item("Set GitHub token…", () -> TokenDialog.show(brand, settings, secrets, changed)))
            .addItem(TrayShell.item("SSH keys…", () -> KeysWindow.show(brand, settings.getAccount().stream().map(a -> new KeysWindow.Entry(a.getId(), a.getName())).toList(), secrets.keys,
                "Add it on GitHub, signed in as that account: Settings, SSH and GPG keys, New SSH key.", changed)))
            .addItem(TrayShell.item("Open settings.toml", () -> open(Places.settings().toString())))
            .describe("guards git and GitHub: SSH keys and GitHub tokens", "https://github.com/leasegate/gitgate")
            .quit("Quit gitgate (git and gh stop working until it starts again)", vault::close)
            .install();
    }

    String line(Secrets secrets, LeaseEngine<GitScope> engine) {
        var keys = secrets.keys.all().size();
        var tokens = settings.getAccount().stream().filter(a -> secrets.tokenFor(a.getId(), false).isPresent()).count();
        return settings.getAccount().size() + " account(s) · " + keys + " key(s) · " + tokens + " token(s) · " + engine.activeGrants().size() + " approval(s)";
    }

    /// settings.toml changed (an account, an organisation, a folder): gitgate's git and ssh files are
    /// written again at once, so the official git follows without a restart. A malformed file is
    /// reported and the files are left as they were.
    static void watchSettings() {
        try (var w = java.nio.file.FileSystems.getDefault().newWatchService()) {
            Places.home().register(w, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                var key = w.take();
                var changed = key.pollEvents().stream().anyMatch(e -> Places.settings().getFileName().equals(e.context()));
                key.reset();
                if (!changed) continue;
                Thread.sleep(300);  // an editor writes in more than one step
                try {
                    var s = SettingsStore.load();
                    if (GitSetup.installed(s)) { GitSetup.writeFiles(s, GitgateMain.selfExe()); System.err.println("gitgate: settings.toml changed; git and ssh files rewritten"); }
                } catch (Exception e) { System.err.println("gitgate: settings.toml changed but could not be used: " + e.getMessage()); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { System.err.println("gitgate: cannot watch " + Places.settings() + ": " + e.getMessage()); }
    }

    static void open(String file) {
        try { new ProcessBuilder("cmd", "/c", "start", "", file).start(); } catch (Exception e) { System.err.println("cannot open " + file); }
    }

    static boolean alreadyRunning() {
        try (var io = Links.open(Places.PRODUCT, Places.runDir(), Places.PIPE)) {
            io.writeFrame("{\"gate\":{\"v\":2,\"op\":\"ping\"}}");
            return io.readFrame() != null;
        } catch (LeaseDeniedException e) { return false; }
    }
}
