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
        stage.setTitle(Places.PRODUCT);
        var vault = VaultUnlock.open(gate, Places.vault(), Places.PRODUCT);
        var secrets = new Secrets(vault);
        secrets.keys.writeAllPublic();

        var rules = new GitRules(settings);
        var engine = new LeaseEngine<GitScope>(rules, new BlanketModeApproval<>(new FxApproval<>(gate, rules), gate),
            new KeyedAppStore<>(Places.keyedApps(), rules));
        engine.trustApproval(new FxTrust<>(gate, rules));
        engine.onExpiring(e -> Platform.runLater(() -> ExpiryWarningWindow.show(engine, gate, e)));
        engine.startSweep();

        var control = new ControlHandler(engine, secrets, settings);
        var controlPipe = LeaseServer.open(Places.runDir(), Places.PIPE);
        var agentPipe = LeaseServer.open(Places.runDir(), Places.AGENT_PIPE);
        var agent = new AgentServer(secrets.keys::keys, new AgentPolicy(engine, secrets));
        Thread.ofPlatform().name("gitgate-control").daemon().start(() -> LeaseServer.serve(controlPipe, "gitgate", control::handle));
        Thread.ofPlatform().name("gitgate-agent").daemon().start(() -> agent.serve(agentPipe, "gitgate-agent"));

        Runnable changed = () -> {};
        var tray = new TrayShell<>(stage, engine, gate, GateIcon.image(GitgateApp.class, "tray.png", "#2e7d32"), () -> line(secrets, engine));
        tray.addItem(TrayShell.item("Set GitHub token…", () -> TokenDialog.show(settings, secrets, changed)))
            .addItem(TrayShell.item("SSH keys…", () -> KeysWindow.show(Places.PRODUCT, settings.getAccount().stream().map(a -> new KeysWindow.Entry(a.getId(), a.getName())).toList(), secrets.keys,
                "Add it on GitHub, signed in as that account: Settings, SSH and GPG keys, New SSH key.", changed)))
            .addItem(TrayShell.item("Import the old credentials…", () -> ImportWindow.show(settings, secrets, changed)))
            .addItem(TrayShell.item("Open settings.toml", () -> open(Places.settings().toString())))
            .quit("Quit gitgate (git and gh stop working until it starts again)", vault::close)
            .install();
    }

    String line(Secrets secrets, LeaseEngine<GitScope> engine) {
        var keys = secrets.keys.all().size();
        var tokens = settings.getAccount().stream().filter(a -> secrets.tokenFor(a.getId(), false).isPresent()).count();
        return settings.getAccount().size() + " account(s) · " + keys + " key(s) · " + tokens + " token(s) · " + engine.activeGrants().size() + " approval(s)";
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
