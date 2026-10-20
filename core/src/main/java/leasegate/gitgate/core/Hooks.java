package leasegate.gitgate.core;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/// What gitgate's two git hooks do, for the official git (see GitSetup). Exit codes are the hook's.
public final class Hooks {

    public record Choice(String account, boolean anonymous) {}

    /// Asks the human (the tray's window); empty when nobody answered or the tray is not running.
    public interface Asker extends Function<RepoDecision.Ask, Optional<Choice>> {}

    /// An unrecorded repository: decided (automatically when the sources agree, else by asking) and
    /// recorded. git read its configuration before this hook ran, so if the identity this commit was
    /// started under is not the decided one, this commit is stopped and the next one is right.
    public static int preCommit(Settings s, Path dir, Asker ask, PrintStream err) {
        var git = s.getBinaries().getGit();
        var repo = RepoConfig.find(dir);
        if (repo.isEmpty()) return 0;
        Account account;
        boolean anonymous;
        String how;
        switch (RepoDecision.resolve(s, dir, null, null)) {
            case RepoDecision.Refuse r -> { err.println("gitgate: " + r.message()); return 1; }
            case RepoDecision.Plain p -> { return 0; }
            case RepoDecision.Decided d -> { account = d.account(); anonymous = d.anonymous(); how = d.how(); }
            case RepoDecision.Ask a -> {
                err.println("gitgate: which account is " + repo.get().workTree() + "? " + a.why() + ". Answer in the GITGATE window.");
                var c = ask.apply(a);
                if (c.isEmpty()) {
                    err.println("gitgate: not decided, so not committed. Start gitgate.exe and commit again, or decide it here: gitgate assign <account> [--anonymous]");
                    return 1;
                }
                account = s.byId(c.get().account()).orElseThrow();
                anonymous = c.get().anonymous();
                how = "decided in the gitgate window";
            }
        }
        var want = s.identity(account, anonymous);
        var now = new Identity(config(git, repo.get().workTree(), "user.name"), config(git, repo.get().workTree(), "user.email"));
        RepoRecord.write(git, repo.get().workTree(), s, account, anonymous, how);
        if (s.shared(now.email()) || (want.name().equals(now.name()) && want.email().equalsIgnoreCase(now.email()))) return 0;
        err.println("gitgate: " + repo.get().workTree() + " is now recorded as " + account.getId() + " (" + want + ").");
        err.println("gitgate: this commit was started as " + now + ", so it was stopped. Run the commit again.");
        return 1;
    }

    /// Before anything leaves: the repository's account, the account of the organisation being
    /// pushed to, and every unpushed commit's author and committer must agree; no forbidden term.
    /// GITGATE_ALLOW_AUTHOR=1 / GITGATE_ALLOW_FORBIDDEN=1 let a push through deliberately.
    public static int prePush(Settings s, Path dir, String url, Asker ask, PrintStream err) {
        var git = s.getBinaries().getGit();
        var repo = RepoConfig.find(dir);
        if (repo.isEmpty()) return 0;
        Account account;
        boolean anonymous;
        switch (RepoDecision.resolve(s, dir, null, null)) {
            case RepoDecision.Refuse r -> { err.println("gitgate: push refused. " + r.message()); return 1; }
            case RepoDecision.Plain p -> { return 0; }
            case RepoDecision.Decided d -> {
                account = d.account();
                anonymous = d.anonymous();
                if (d.record()) RepoRecord.write(git, repo.get().workTree(), s, account, anonymous, d.how());
            }
            case RepoDecision.Ask a -> {
                err.println("gitgate: which account is " + repo.get().workTree() + "? " + a.why() + ". Answer in the GITGATE window.");
                var c = ask.apply(a);
                if (c.isEmpty()) { err.println("gitgate: push refused: the repository's account is not decided (gitgate assign <account>)."); return 1; }
                account = s.byId(c.get().account()).orElseThrow();
                anonymous = c.get().anonymous();
                RepoRecord.write(git, repo.get().workTree(), s, account, anonymous, "decided in the gitgate window");
            }
        }
        var target = RepoConfig.orgOf(url).flatMap(org -> OrgMemory.account(s, org));
        if (target.isPresent() && !target.get().getId().equalsIgnoreCase(account.getId())) {
            err.println("gitgate: push refused. This repository is " + account.getId() + ", and " + url + " belongs to " + target.get().getId()
                + ": its commits would arrive there under the wrong account. Push to a remote of " + account.getId() + ", or change the record: gitgate assign " + target.get().getId());
            return 1;
        }
        var allowed = s.allowedEmails(account, anonymous);
        var strangers = IdentityGuard.scan(git, repo.get().workTree(), allowed);
        if (!strangers.isEmpty() && !"1".equals(System.getenv("GITGATE_ALLOW_AUTHOR"))) {
            err.println("gitgate: push refused. This repository pushes as " + account.getId() + " (" + s.identity(account, anonymous) + "), and these unpushed commits carry another identity:");
            strangers.forEach(x -> err.println("  " + x.commit() + "  author " + x.author() + "  committer " + x.committer()));
            err.println("Rewrite them (not pushed yet): git rebase -r <upstream, or --root> --exec \"git commit --amend --no-edit --reset-author\"");
            err.println("If they are rightly someone else's, push with GITGATE_ALLOW_AUTHOR=1.");
            return 1;
        }
        var paths = PushGuard.paths(git, repo.get().workTree(), account);
        if (!paths.isEmpty() && !"1".equals(System.getenv("GITGATE_ALLOW_PATHS"))) {
            err.println("gitgate: push refused. Account " + account.getId() + " never publishes these files, and unpushed commits add or change them:");
            paths.forEach(p -> err.println("  " + p));
            err.println("Take them out of those commits (git rm --cached, then rewrite the unpushed commits), or push with GITGATE_ALLOW_PATHS=1 if this is deliberate.");
            return 1;
        }
        var hits = PushGuard.scan(git, repo.get().workTree(), account);
        if (!hits.isEmpty() && !"1".equals(System.getenv("GITGATE_ALLOW_FORBIDDEN"))) {
            err.println("gitgate: push refused. These commits carry terms that account " + account.getId() + " must never publish:");
            hits.forEach(h -> err.println("  " + h.commit() + "  [" + h.term() + "]  " + h.line().trim()));
            err.println("Rewrite those commits, or, if a hit is a false match, push with GITGATE_ALLOW_FORBIDDEN=1.");
            return 1;
        }
        return 0;
    }

    static String config(String git, Path repo, String key) {
        try {
            var p = new ProcessBuilder(git, "-C", repo.toString(), "config", key).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 ? out : "";
        } catch (Exception e) { return ""; }
    }

    private Hooks() {}
}
