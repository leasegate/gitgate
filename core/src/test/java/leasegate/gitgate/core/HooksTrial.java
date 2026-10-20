package leasegate.gitgate.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Proof for running the OFFICIAL git through gitgate (GitSetup + Hooks), in a sandbox: run it with
/// GIT_CONFIG_GLOBAL pointing at a scratch file, so the real ~/.gitconfig is never read or written;
/// user.home and gitgate.home are scratch folders too. Real git, real repositories, nothing pushed.
/// GIT_CONFIG_GLOBAL=<scratch>/global.gitconfig java -cp <classes> leasegate.gitgate.core.HooksTrial <real git.exe> <scratch dir>
public class HooksTrial {

    static int failed;
    static String git;

    public static void main(String[] args) throws Exception {
        git = args[0];
        var root = Path.of(args[1]).toRealPath();
        var global = Path.of(System.getenv("GIT_CONFIG_GLOBAL"));
        check("sandbox: GIT_CONFIG_GLOBAL is inside the scratch folder", global.toAbsolutePath().normalize().startsWith(root));
        System.setProperty("user.home", root.resolve("userhome").toString());
        System.setProperty("gitgate.home", root.resolve("gghome").toString());
        Files.writeString(global, "[user]\n\tname = User\n\temail = user@example.com\n");
        Files.createDirectories(root.resolve("userhome/.ssh"));
        Files.writeString(root.resolve("userhome/.ssh/config"), "Host other\n    HostName other.example\n");
        var work = Files.createDirectories(root.resolve("work"));
        var toml = root.resolve("settings.toml");
        Files.writeString(toml, String.join("\n",
            "[binaries]", "git = '" + git + "'", "",
            "[newRepo]", "defaultAccount = 'aa'", "",
            "[[account]]", "id = 'aa'", "name = 'Alpha'", "userName = 'alpha'", "userEmail = 'alpha@example.org'", "orgs = ['alpha-org']", "paths = ['" + work + "']", "",
            "[[account]]", "id = 'bb'", "name = 'Beta'", "userName = 'beta'", "userEmail = 'beta@example.org'", "orgs = ['beta-org']", ""));
        var s = SettingsStore.load(toml);

        var done = GitSetup.install(s, "C:/fake/gitgate.exe");
        var g = Files.readString(global);
        check("install: one include line in the global config", g.contains("path = " + GitSetup.slash(GitSetup.config())));
        var mine = Files.readString(GitSetup.config());
        check("gitgate's config: ssh = Windows OpenSSH path, hooksPath, includeIf by org and by folder",
            mine.contains("sshCommand = ") && mine.contains("hooksPath = ") && mine.contains("hasconfig:remote.*.url:git@github.com:alpha-org/**") && mine.contains("gitdir/i:" + work.toString().replace('\\', '/') + "/"));
        var ssh = Files.readString(root.resolve("userhome/.ssh/config"));
        check("~/.ssh/config: one Include line first, the agent in gitgate's own file, the user's own block kept", ssh.startsWith(GitSetup.SSH_MARK) && ssh.contains("Include ") && Files.readString(GitSetup.sshInclude()).contains("IdentityAgent //./pipe/") && ssh.contains("Host other"));
        check("hooks written", Files.exists(GitSetup.hooks().resolve("pre-commit")) && Files.exists(GitSetup.hooks().resolve("pre-push")));
        check("install again changes nothing", GitSetup.install(s, "C:/fake/gitgate.exe").size() == 3);

        var other = Files.createDirectories(root.resolve("elsewhere"));
        var rb = repo(other.resolve("rb"));
        g(rb, "remote", "add", "origin", "git@github.com:beta-org/rb.git");
        check("official git, no record: identity from the remote's org (includeIf hasconfig)", "beta@example.org".equals(cfg(rb, "user.email")));
        var ra = repo(work.resolve("ra"));
        check("official git, no record: identity from the folder (includeIf gitdir)", "alpha@example.org".equals(cfg(ra, "user.email")));
        var ru = repo(other.resolve("ru"));
        check("official git, nothing names it: the placeholder", "user@example.com".equals(cfg(ru, "user.email")));

        var err = new ByteArrayOutputStream();
        var out = new PrintStream(err, true);
        Hooks.Asker never = a -> { throw new AssertionError("must not ask"); };
        check("pre-commit, sources agree and the identity is already right: recorded, commit goes on", Hooks.preCommit(s, ra, never, out) == 0 && "aa".equals(cfg(ra, "gitgate.account")));
        check("pre-commit, nothing names it, the human picks bb: recorded, this commit stopped (it began as the placeholder)",
            Hooks.preCommit(s, ru, a -> Optional.of(new Hooks.Choice("bb", false)), out) == 1 && "bb".equals(cfg(ru, "gitgate.account")) && "beta@example.org".equals(cfg(ru, "user.email")));
        var rn = repo(other.resolve("rn"));
        check("pre-commit, nobody answers: stopped, nothing recorded", Hooks.preCommit(s, rn, a -> Optional.empty(), out) == 1 && cfg(rn, "gitgate.account").isEmpty());

        commit(ra, "alpha", "alpha@example.org", "one");
        check("pre-push to the account's own org: allowed", Hooks.prePush(s, ra, "git@github.com:alpha-org/ra.git", never, out) == 0);
        check("pre-push to another account's org: refused", Hooks.prePush(s, ra, "git@github.com:beta-org/ra.git", never, out) == 1);
        commit(ra, "beta", "beta@example.org", "two");
        check("pre-push with a commit under the other identity: refused", Hooks.prePush(s, ra, "git@github.com:alpha-org/ra.git", never, out) == 1);
        check("...and the reason names the commit", err.toString().contains("carry another identity"));

        GitSetup.undo(s);
        check("undo: the include line is gone, the rest of the global config kept", !Files.readString(global).contains(GitSetup.slash(GitSetup.config())) && Files.readString(global).contains("user@example.com"));
        check("undo: ~/.ssh/config is the user's own again", Files.readString(root.resolve("userhome/.ssh/config")).equals("Host other\n    HostName other.example\n"));

        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static Path repo(Path dir) throws Exception {
        Files.createDirectories(dir);
        g(dir, "init", "-q", "--initial-branch=main");
        return dir;
    }

    static void commit(Path repo, String name, String email, String msg) throws Exception {
        Files.writeString(repo.resolve(msg + ".txt"), msg);
        g(repo, "add", ".");
        g(repo, "-c", "user.name=" + name, "-c", "user.email=" + email, "-c", "core.hooksPath=", "commit", "-q", "-m", msg);
    }

    static String cfg(Path repo, String key) throws Exception {
        var p = new ProcessBuilder(git, "config", key).directory(repo.toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes()).trim();
        return p.waitFor() == 0 ? out : "";
    }

    static void g(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of(git));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }
}
