package leasegate.gitgate.cli;

import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import leasegate.gitgate.core.*;
import leasegate.transport.LeaseDeniedException;

/// `git1st`: makes the current folder a repository and gives it a home on GitHub, under the right
/// account. The folder's name is the repository's name. It asks for the account (the one this
/// folder belongs to is the default), the organisation, the .gitignore template and the
/// visibility, shows what it will do, and only then: init on main, the .gitignore, a first commit,
/// the forbidden-terms check, the repository on GitHub, an SSH remote (never https, which an agent
/// shell cannot authenticate), and the first push. Every git and gh call goes through the gate.
final class Git1st {

    final String pinned;
    final Scanner in = new Scanner(System.in);
    Settings s;
    Account account;
    String token;

    Git1st(String pinned) { this.pinned = pinned; }

    int run() {
        s = SettingsStore.load();
        var here = Path.of("").toAbsolutePath();
        var repo = here.getFileName().toString();
        account = chooseAccount();
        var org = choose("organisation", account.getOrgs(), 0);
        var ignore = chooseGitignore();
        var isPrivate = !"2".equals(ask("visibility: 1 private (default), 2 public", "1"));
        System.out.println();
        System.out.println("repository : git@github.com:" + org + "/" + repo + ".git" + (isPrivate ? "  (private)" : "  (PUBLIC)"));
        System.out.println("folder     : " + here);
        System.out.println("account    : " + account.getId() + " (" + account.getName() + ")");
        System.out.println("gitignore  : " + (ignore == null ? "(none)" : ignore));
        if (!yes("Create it? (y/N)")) { System.out.println("Nothing was done."); return 0; }

        if (!Files.exists(here.resolve(".git"))) git("init", "--initial-branch=main");
        copyGitignore(here, ignore);
        git("config", "core.safecrlf", "false");
        git("add", ".");
        var commit = gitCaptured("commit", "-m", "Initial commit");
        if (commit.exit() != 0 && !commit.out().contains("nothing to commit")) throw new IllegalStateException("the first commit failed: " + commit.out());

        var hits = PushGuard.scan(s.getBinaries().getGit(), here, account);
        if (!hits.isEmpty()) return GitRun.refuse(account, hits);

        var full = org + "/" + repo;
        var exists = gh("repo", "view", full).exit() == 0;
        if (exists) {
            System.out.println(full + " already exists on GitHub.");
            if (!yes("Push to it anyway? (y/N)")) return 0;
        } else {
            var made = gh("repo", "create", full, isPrivate ? "--private" : "--public");
            if (made.exit() != 0) throw new IllegalStateException("gh repo create failed: " + made.out());
            System.out.println("created " + full + " on GitHub");
        }
        var url = "git@github.com:" + full + ".git";
        if (gitCaptured("remote", "get-url", "origin").exit() == 0) git("remote", "set-url", "origin", url); else git("remote", "add", "origin", url);
        if (git("push", "-u", "origin", "main") != 0) throw new IllegalStateException("the first push failed (see above)");
        System.out.println();
        System.out.println("https://github.com/" + full + "   (" + (isPrivate ? "private" : "public") + ", pushed as " + account.getId() + ")");
        return 0;
    }

    Account chooseAccount() {
        if (pinned != null) return s.byId(pinned).orElseThrow(() -> new IllegalStateException("no account " + pinned));
        var here = AccountMatcher.match(s, Path.of("")).map(m -> m.account().getId())
            .orElse(s.getNewRepo().getDefaultAccount());
        var ids = s.getAccount().stream().map(Account::getId).toList();
        var picked = choose("account", ids, Math.max(0, ids.indexOf(here)));
        return s.byId(picked).orElseThrow(() -> new IllegalStateException("no account " + picked));
    }

    String chooseGitignore() {
        var dir = s.getNewRepo().getGitignoreTemplates();
        if (dir == null || !Files.isDirectory(Path.of(dir))) return null;
        try (var files = Files.list(Path.of(dir))) {
            var names = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".gitignore"))
                .map(n -> n.substring(0, n.length() - ".gitignore".length())).sorted().toList();
            if (names.isEmpty()) return null;
            return choose("gitignore", names, Math.max(0, names.indexOf(s.getNewRepo().getGitignore())));
        } catch (Exception e) { return null; }
    }

    /// Only when the folder has none of its own.
    void copyGitignore(Path here, String ignore) {
        var target = here.resolve(".gitignore");
        if (ignore == null || Files.exists(target)) return;
        var source = Path.of(s.getNewRepo().getGitignoreTemplates(), ignore + ".gitignore");
        try { if (Files.exists(source)) Files.copy(source, target); }
        catch (Exception e) { throw new IllegalStateException("cannot copy " + source + ": " + e.getMessage()); }
    }

    /// A numbered list; Enter takes the default, a number picks, anything else is taken as typed.
    String choose(String what, List<String> options, int def) {
        if (options.isEmpty()) return ask(what, null);
        System.out.println();
        for (var i = 0; i < options.size(); i++) System.out.println("  " + (i + 1) + ". " + options.get(i) + (i == def ? "  (default)" : ""));
        var a = ask(what + " (1-" + options.size() + ", or type one)", String.valueOf(def + 1));
        try {
            var n = Integer.parseInt(a);
            if (n >= 1 && n <= options.size()) return options.get(n - 1);
        } catch (NumberFormatException typed) { return a; }
        return options.get(def);
    }

    String ask(String prompt, String def) {
        System.out.print(prompt + (def == null ? "" : " [" + def + "]") + ": ");
        var line = in.hasNextLine() ? in.nextLine().trim() : "";
        return line.isEmpty() && def != null ? def : line;
    }

    boolean yes(String prompt) { return ask(prompt, "n").toLowerCase().startsWith("y"); }

    int git(String... args) { return GitRun.exec(GitRun.command(s, account, List.of(args)), GitRun.env(account)); }

    Captured gitCaptured(String... args) { return Captured.run(GitRun.command(s, account, List.of(args)), GitRun.env(account)); }

    Captured gh(String... args) {
        if (token == null) {
            try { token = Gate.token(account.getId(), Tier.WRITE, "git1st: gh repo create", "git1st"); }
            catch (LeaseDeniedException e) { throw new IllegalStateException(e.getMessage()); }
        }
        return Captured.run(GhRun.command(s, List.of(args)), Map.of("GITHUB_TOKEN", token, "GH_TOKEN", token, "GH_PROMPT_DISABLED", "1"));
    }
}
