package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Proof for "which account is this repository": real repositories in a temp folder, the real git
/// (never gitgate's), nothing pushed anywhere. Covers the four sources and how they combine, the
/// record written into a repository and read back (also through a worktree's `.git` file), the push
/// identity check, and the audit telling a leak from someone else's commit.
/// java -cp <classes> leasegate.gitgate.core.RepoDecisionTrial <path to the real git.exe>
public class RepoDecisionTrial {

    static int failed;
    static String git;

    public static void main(String[] args) throws Exception {
        git = args.length > 0 ? args[0] : "git";
        var root = Files.createTempDirectory("gitgate-repo-trial").toRealPath();
        System.setProperty("gitgate.home", root.resolve("home").toString());
        var work = root.resolve("work");
        var other = root.resolve("other");
        Files.createDirectories(work);
        Files.createDirectories(other);
        var toml = root.resolve("settings.toml");
        Files.writeString(toml, String.join("\n",
            "[binaries]", "git = '" + git + "'", "",
            "[[account]]", "id = 'aa'", "name = 'Alpha'", "userName = 'alpha'", "userEmail = 'alpha@example.org'",
            "orgs = ['alpha-org']", "paths = ['" + work + "']", "",
            "[[account]]", "id = 'bb'", "name = 'Beta'", "userName = 'beta'", "userEmail = 'beta@example.org'", "alsoEmails = ['beta@users.noreply.example']",
            "orgs = ['beta-org']", ""));
        var s = SettingsStore.load(toml);

        check("orgOf: scp form", RepoConfig.orgOf("git@github.com:alpha-org/x.git").orElse("").equals("alpha-org"));
        check("orgOf: https", RepoConfig.orgOf("https://github.com/beta-org/x").orElse("").equals("beta-org"));
        check("orgOf: ssh url", RepoConfig.orgOf("ssh://git@github.com/beta-org/x.git").orElse("").equals("beta-org"));
        check("orgOf: host alias", RepoConfig.orgOf("git@github.com-bb:beta-org/x.git").orElse("").equals("beta-org"));
        check("orgOf: not GitHub", RepoConfig.orgOf("git@gitlab.com:beta-org/x.git").isEmpty());

        var r1 = repo(work.resolve("r1"));
        check("folder rule alone decides, and asks for a record", decided(s, r1, null, "aa", true));
        RepoRecord.write(git, r1, s, s.byId("aa").orElseThrow(), false, "trial");
        var c1 = RepoConfig.find(r1.resolve("sub")).orElseThrow();
        check("the record is read back from a subfolder", "aa".equals(c1.account()) && !c1.anonymous() && "alpha@example.org".equals(c1.userEmail()));
        check("recorded and agreeing: no record needed", decided(s, r1, null, "aa", false));
        check("recorded repo + alias of another account: refused", RepoDecision.resolve(s, r1, "bb", null) instanceof RepoDecision.Refuse);
        check("the list of seen repositories has it", RepoRecord.seen().containsKey(r1.toString()));

        g(r1, "config", "user.email", "someone@else.example");
        check("recorded but the identity in the repo drifted: record again", decided(s, r1, null, "aa", true));

        var r2 = repo(work.resolve("r2"));
        g(r2, "remote", "add", "origin", "git@github.com:beta-org/r2.git");
        check("folder says aa, remote org says bb: ask", RepoDecision.resolve(s, r2, null, null) instanceof RepoDecision.Ask a && a.suggested() == null);
        check("...and an alias cannot settle it", RepoDecision.resolve(s, r2, "aa", null) instanceof RepoDecision.Refuse);

        var r3 = repo(other.resolve("r3"));
        check("no rule names it: ask", RepoDecision.resolve(s, r3, null, null) instanceof RepoDecision.Ask);
        check("not a repository and no rule: plain git", RepoDecision.resolve(s, other, null, null) instanceof RepoDecision.Plain);
        check("an alias in an unruled repository decides", decided(s, r3, "bb", "bb", true));
        g(r3, "remote", "add", "origin", "https://github.com/beta-org/r3");
        check("a known remote org decides an unruled repository", decided(s, r3, null, "bb", true));
        check("clone: the URL's org decides before the repository exists", RepoDecision.resolve(s, other, null, "git@github.com:alpha-org/new.git") instanceof RepoDecision.Decided d && d.account().getId().equals("aa"));

        RepoRecord.write(git, r3, s, s.byId("bb").orElseThrow(), true, "trial");
        var c3 = RepoConfig.find(r3).orElseThrow();
        check("anonymous: recorded, and the repo carries the anonymous identity", c3.anonymous() && "user@example.com".equals(c3.userEmail()) && "User".equals(c3.userName()));
        check("anonymous recorded repo decides with no new record", RepoDecision.resolve(s, r3, null, null) instanceof RepoDecision.Decided d && d.anonymous() && !d.record());

        g(r1, "worktree", "add", root.resolve("wt").toString());
        var wt = RepoConfig.find(root.resolve("wt"));
        check("a worktree reads the main repository's record through its .git file", wt.isPresent() && "aa".equals(wt.get().account()));

        commit(r2, "beta", "beta@example.org", "one");
        commit(r2, "alpha", "alpha@example.org", "two");
        var strangers = IdentityGuard.scan(git, r2, s.byId("bb").orElseThrow().ownEmails());
        check("push check: the commit under the other identity is found, the own one is not", strangers.size() == 1 && strangers.getFirst().author().contains("alpha@example.org"));
        commit(r3, "beta", "beta@users.noreply.example", "noreply");
        check("push check: an account's alsoEmails count as its own", IdentityGuard.scan(git, r3, s.byId("bb").orElseThrow().ownEmails()).isEmpty());

        RepoRecord.write(git, r2, s, s.byId("bb").orElseThrow(), false, "trial");
        commit(r2, "someone", "someone@third.example", "three");
        g(r2, "remote", "remove", "origin");
        var f = Audit.check(SettingsStore.load(toml), r2);
        check("audit: one leak (alpha's commit in bb's repo), a third party's commit is not one", f != null && f.local() == 1 && f.pushed() == 0);
        check("audit finds the repositories under a folder", Audit.repos(root, 4).containsAll(List.of(r1, r2, r3)));

        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static boolean decided(Settings s, Path dir, String pinned, String account, boolean record) {
        var r = RepoDecision.resolve(s, dir, pinned, null);
        if (r instanceof RepoDecision.Decided d && d.account().getId().equals(account) && d.record() == record) return true;
        System.out.println("      got " + r);
        return false;
    }

    static Path repo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sub"));
        g(dir, "init", "-q", "--initial-branch=main");
        return dir;
    }

    static void commit(Path repo, String name, String email, String msg) throws Exception {
        Files.writeString(repo.resolve(msg + ".txt"), msg);
        g(repo, "add", ".");
        g(repo, "-c", "user.name=" + name, "-c", "user.email=" + email, "commit", "-q", "-m", msg);
    }

    static void g(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of(git));
        if (args[0].equals("-c")) { cmd.addAll(List.of(args)); }
        else cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }
}
