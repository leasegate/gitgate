package leasegate.gitgate.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import leasegate.core.LeaseEngine;
import leasegate.core.LeaseServer;
import leasegate.core.access.*;
import leasegate.gitgate.core.*;
import leasegate.sshagent.AgentKey;
import leasegate.sshagent.AgentServer;
import leasegate.vault.Vault;

/// gitgate end to end, with no window and nothing real at stake: a scratch home and pipe, a
/// throwaway SSH key and a fake token in a scratch vault, an approver that says yes (or no) and
/// records what it was asked. The real CLI jar then runs as a separate process: `git ls-remote`
/// over Windows OpenSSH reaches GitHub through the agent (GitHub refuses the throwaway key, which
/// is expected -- what matters is that the agent was asked, with the right tier and repository);
/// `gh api user` gets the fake token (GitHub answers 401); `gh auth token` never asks at all.
/// java --enable-native-access=ALL-UNNAMED -cp <app classes+deps> leasegate.gitgate.app.WireTrial <gitgate-cli.jar> <the real gh.exe>
public class WireTrial {

    static int failed;
    static final List<Approval.Request<GitScope>> asked = new CopyOnWriteArrayList<>();
    static volatile boolean say = true;
    static final java.util.concurrent.atomic.AtomicInteger listed = new java.util.concurrent.atomic.AtomicInteger();

    public static void main(String[] args) throws Exception {
        var cli = Path.of(args[0]).toAbsolutePath();
        var home = Files.createTempDirectory("gitgate-wire");
        var pipe = "gitgate-wire-" + ProcessHandle.current().pid();
        System.setProperty("gitgate.home", home.toString());
        System.setProperty("gitgate.pipe", pipe);
        Files.writeString(home.resolve("settings.toml"), String.join("\n",
            "[binaries]", "git = 'git'", "gh = " + q(args[1]), "ssh = 'C:/Windows/System32/OpenSSH/ssh.exe'", "",
            "[output]", "accountLine = 'stderr'", "",
            "[[account]]", "id = 'trial'", "name = 'Trial'", "userName = 'trial'", "userEmail = 'trial@example.org'",
            "paths = [" + q(home.toString()) + "]", "ssh = 'gate'", ""));
        var settings = SettingsStore.load(home.resolve("settings.toml"));
        var vault = Vault.create(home.resolve("vault.json"), "Trialpass1").vault();
        var secrets = new Secrets(vault);
        secrets.keys.put("trial", AgentKey.generate("trial@gitgate"));
        vault.put(Secrets.token("trial", false), "fake-token-for-the-wire-trial");
        var rules = new GitRules(settings);
        Approval<GitScope> approver = r -> {
            asked.add(r);
            return say ? Optional.of(new Approval.Decision<>(r.chain().getFirst(), 30, 30, r.requested())) : Optional.empty();
        };
        var engine = new LeaseEngine<GitScope>(rules, approver, new KeyedAppStore<>(home.resolve("keyed.json"), rules));
        var control = LeaseServer.open(home.resolve("run"), Places.PIPE);
        var agentPipe = LeaseServer.open(home.resolve("run"), Places.AGENT_PIPE);
        var handler = new ControlHandler(engine, secrets, settings);
        Thread.ofPlatform().daemon().start(() -> LeaseServer.serve(control, "wire", handler::handle));
        Thread.ofPlatform().daemon().start(() -> new AgentServer(() -> { listed.incrementAndGet(); return secrets.keys.keys(); }, new AgentPolicy(engine, secrets)).serve(agentPipe, "wire-agent"));

        var ls = cli(cli, home, pipe, "git", "ls-remote", "git@github.com:leasegate/leasegate.git");
        check("ssh, started by the git proxy, asked the gate's agent for its keys", listed.get() >= 1);
        check("GitHub turned the throwaway key down when it was offered, so no signature was asked for", ls.contains("Permission denied") && asked.isEmpty());
        var target = SshTarget.of("C:/Windows/System32/OpenSSH/ssh.exe -o IdentitiesOnly=yes git@github.com \"git-receive-pack 'leasegate/gitgate.git'\"");
        check("a push command line reads as WRITE to that repository", target.tier() == Tier.WRITE && target.detail().equals("git push to leasegate/gitgate"));

        asked.clear();
        var api = cli(cli, home, pipe, "gh", "api", "user");
        check("gh asked the gate for a read token", asked.size() == 1 && asked.getFirst().requested().tier() == Tier.READ);
        check("...and gh used the token it was given (GitHub: bad credentials)", api.contains("Bad credentials") || api.contains("401"));

        asked.clear();
        var tok = cli(cli, home, pipe, "gh", "auth", "token");
        check("gh auth token is refused before the gate is asked", asked.isEmpty() && tok.contains("refused") && !tok.contains("fake-token"));

        asked.clear();
        say = false;
        var denied = cli(cli, home, pipe, "gh", "repo", "delete", "leasegate/nothing", "--yes");
        check("an admin request is put to the human as ADMIN", asked.size() == 1 && asked.getFirst().requested().tier() == Tier.ADMIN);
        check("...and when the human says no, gh never runs", denied.contains("not approved"));

        System.out.println(failed == 0 ? "ALL CHECKS PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String cli(Path jar, Path home, String pipe, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("java", "--enable-native-access=ALL-UNNAMED", "-Dgitgate.home=" + home, "-Dgitgate.pipe=" + pipe, "-jar", jar.toString()));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        System.out.println("  $ " + String.join(" ", args) + "\n    " + out.trim().replace("\n", "\n    "));
        return out;
    }

    static String q(String s) { return "'" + s + "'"; }

    static void check(String label, boolean ok) {
        if (!ok) failed++;
        System.out.println((ok ? "ok - " : "FAILED - ") + label);
    }
}
