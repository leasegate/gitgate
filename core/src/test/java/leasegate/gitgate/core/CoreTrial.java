package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Pure logic proof for gitgate-core: settings parse, an account is chosen by directory in every
/// slash style, the gh classifier refuses what would leak the token and puts what cannot be undone
/// behind ADMIN, scopes cover the right things, and the push guard finds a forbidden term in an
/// unpushed commit (a real git repository in a temp folder, nothing pushed anywhere).
/// java -cp <classes> leasegate.gitgate.core.CoreTrial
public class CoreTrial {

    static int failed;

    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("gitgate-core-trial");
        var toml = dir.resolve("settings.toml");
        Files.writeString(toml, String.join("\n",
            "[binaries]", "git = 'git'", "",
            "[[account]]", "id = 'work'", "name = 'Work'", "userName = 'worker'", "userEmail = 'worker@example.org'",
            "paths = ['C:\\code\\work']", "pathRegex = ['.*/work-[a-z]+/.*']", "forbidden = ['secret-project', 'acme\\s+internal']", "",
            "[[account]]", "id = 'home'", "name = 'Home'", "paths = ['C:\\code\\home']", "ssh = 'C:\\keys\\home'", ""));
        var s = SettingsStore.load(toml);
        check("two accounts parsed, Windows paths in single quotes kept", s.getAccount().size() == 2 && s.byId("work").orElseThrow().getPaths().getFirst().equals("C:\\code\\work"));
        check("ssh defaults to the gate; a file path means the old way", s.byId("work").orElseThrow().viaGate() && !s.byId("home").orElseThrow().viaGate());

        check("a folder under a path matches", id(s, "C:\\code\\work\\repo") .equals("work"));
        check("case and slash style do not matter", id(s, "c:/CODE/Work/repo").equals("work"));
        check("a Git Bash or WSL style entry reads as the Windows path", AccountMatcher.normalize("/c/code/home/").equals("c:/code/home") && AccountMatcher.normalize("/mnt/d/x").equals("d:/x"));
        check("a sibling with a longer name does not", id(s, "C:\\code\\workshop\\x").equals("-"));
        check("pathRegex matches on forward slashes", id(s, "D:\\elsewhere\\work-tools\\x").equals("work"));
        check("no rule, no account", id(s, "D:\\other").equals("-"));

        check("gh auth token is refused", GhCommand.classify(List.of("auth", "token")) instanceof GhCommand.Refused);
        check("gh auth status --show-token is refused", GhCommand.classify(List.of("auth", "status", "--show-token")) instanceof GhCommand.Refused);
        check("gh auth status is a read", tier("auth", "status") == Tier.READ);
        check("gh pr list is a read", tier("pr", "list") == Tier.READ);
        check("gh pr create is a write", tier("pr", "create", "--fill") == Tier.WRITE);
        check("gh repo create is a write", tier("repo", "create", "org/x", "--private") == Tier.WRITE);
        check("gh repo delete is admin", tier("repo", "delete", "org/x", "--yes") == Tier.ADMIN);
        check("gh repo edit (visibility) is admin", tier("repo", "edit", "--visibility", "public") == Tier.ADMIN);
        check("gh secret set is admin", tier("secret", "set", "X") == Tier.ADMIN);
        check("gh alias set is admin", tier("alias", "set", "x", "!echo") == Tier.ADMIN);
        check("gh extension install is admin", tier("extension", "install", "a/b") == Tier.ADMIN);
        check("gh api GET is a read", tier("api", "repos/o/r") == Tier.READ);
        check("gh api with -X DELETE is admin", tier("api", "-X", "DELETE", "repos/o/r") == Tier.ADMIN);
        check("gh api --method=PUT is admin", tier("api", "--method=PUT", "repos/o/r/collaborators/u") == Tier.ADMIN);
        check("gh api with a field is a write", tier("api", "repos/o/r/issues", "-f", "title=x") == Tier.WRITE);
        check("gh api graphql is a write", tier("api", "graphql", "-f", "query=x") == Tier.WRITE);

        var pushWork = new GitScope("work", Tier.WRITE, "git push to o/a", null);
        check("a write grant covers a read of the same account", pushWork.covers(new GitScope("work", Tier.READ, "x", null)));
        check("...and a push to another repo of that account", pushWork.covers(new GitScope("work", Tier.WRITE, "git push to o/b", null)));
        check("...but not admin", !pushWork.covers(new GitScope("work", Tier.ADMIN, "gh repo delete", null)));
        check("...and not another account", !pushWork.covers(new GitScope("home", Tier.READ, null, null)));
        var rules = new GitRules(s);
        check("admin always needs the passphrase", rules.needsPassphrase(new GitScope("work", Tier.ADMIN, "x", null)) && !rules.needsPassphrase(pushWork));
        check("admin is capped at 30 minutes", rules.capByScope(new leasegate.core.access.DurationCaps.Result(1440, "x"), new GitScope("work", Tier.ADMIN, "x", null)).minutes() == 30);
        check("a read is raised to at least 4 hours", rules.capByScope(new leasegate.core.access.DurationCaps.Result(30, "x"), new GitScope("work", Tier.READ, "x", null)).minutes() == 240);

        var repo = Files.createDirectories(dir.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "nothing to see\n");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.org", "commit", "-q", "-m", "clean");
        check("a clean unpushed commit passes the guard", PushGuard.scan("git", repo, s.byId("work").orElseThrow()).isEmpty());
        Files.writeString(repo.resolve("b.txt"), "notes about the Secret-Project\n");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.org", "commit", "-q", "-m", "from ACME  internal planning");
        var hits = PushGuard.scan("git", repo, s.byId("work").orElseThrow());
        check("the guard finds the term in a file, any case", hits.stream().anyMatch(h -> h.term().equalsIgnoreCase("secret-project")));
        check("...and in the commit message, by regex", hits.stream().anyMatch(h -> h.term().toLowerCase().startsWith("acme")));
        check("an account with no forbidden terms is not scanned", PushGuard.scan("git", repo, s.byId("home").orElseThrow()).isEmpty());

        System.out.println(failed == 0 ? "ALL CHECKS PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String id(Settings s, String dir) { return AccountMatcher.match(s, Path.of(dir)).map(m -> m.account().getId()).orElse("-"); }

    static Tier tier(String... args) { return ((GhCommand.Allowed) GhCommand.classify(List.of(args))).tier(); }

    static void git(Path repo, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start().waitFor();
    }

    static void check(String label, boolean ok) {
        if (!ok) failed++;
        System.out.println((ok ? "ok - " : "FAILED - ") + label);
    }
}
