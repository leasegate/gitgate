package leasegate.gitgate.cli;

import java.nio.file.Files;
import java.nio.file.Path;

import leasegate.gitgate.core.*;
import leasegate.transport.LeaseDeniedException;

/// Which account and identity this command runs as. Settles what RepoDecision leaves open: asks the
/// human through the gitgate tray (the terminal waits for the answer) when the command needs an
/// account and nothing decides it, and writes an automatic answer into the repository so that it is
/// asked at most once. A command that needs no account (status, log, diff) is never held up.
final class Choose {

    /// account null: run plain git, with no identity and no key.
    record Run(Account account, boolean anonymous, String line) {}

    static Run decide(Settings s, Path dir, String pinned, String cloneUrl, boolean needsAccount, String tool) {
        var git = s.getBinaries().getGit();
        var result = RepoDecision.resolve(s, dir, pinned, cloneUrl);
        switch (result) {
            case RepoDecision.Refuse r -> throw new IllegalStateException(r.message());
            case RepoDecision.Plain p -> { return new Run(null, false, AccountMatcher.noMatchLine(tool)); }
            case RepoDecision.Decided d -> {
                if (d.record()) RepoConfig.find(dir).ifPresent(r -> RepoRecord.write(git, r.workTree(), s, d.account(), d.anonymous(), d.how()));
                return run(d.account(), d.anonymous(), d.how());
            }
            case RepoDecision.Ask a -> {
                if (!needsAccount) return new Run(null, false, "ACCOUNT(UNDECIDED," + tool + " without an account) ACTIVATEDBY(" + a.why() + ")");
                var repo = RepoConfig.find(dir);
                var where = repo.map(RepoConfig::workTree).orElse(dir.toAbsolutePath().normalize());
                if (!Gate.running())
                    throw new IllegalStateException("which account is " + where + "? " + a.why()
                        + ".\n  gitgate is not running, so it cannot ask. Start gitgate.exe, or decide it here: gitgate assign <account> [--anonymous]"
                        + "\n  accounts: " + String.join(", ", s.getAccount().stream().map(x -> x.getId() + " (" + x.getName() + ")").toList()));
                var remotes = repo.map(r -> String.join("  ", r.remotes().values())).orElse(cloneUrl == null ? "" : cloneUrl);
                var authors = repo.map(r -> RepoRecord.recentAuthors(git, r.workTree(), 4)).orElse(java.util.List.of());
                System.err.println("gitgate: which account is " + where + "? Answer in the GITGATE window.");
                Gate.Choice c;
                try { c = Gate.decide(where.toString(), remotes, a.why(), a.sources(), authors, a.suggested(), tool); }
                catch (LeaseDeniedException e) { throw new IllegalStateException(e.getMessage()); }
                var account = s.byId(c.account()).orElseThrow(() -> new IllegalStateException("gitgate answered with an unknown account " + c.account()));
                repo.ifPresent(r -> RepoRecord.write(git, r.workTree(), s, account, c.anonymous(), "decided in the gitgate window"));
                return run(account, c.anonymous(), "decided in the gitgate window");
            }
        }
    }

    static Run run(Account a, boolean anonymous, String how) {
        return new Run(a, anonymous, "ACCOUNT(%s,%s)%s ACTIVATEDBY(%s)".formatted(a.getId(), a.getName(), anonymous ? " IDENTITY(anonymous)" : "", how));
    }

    /// After `git clone` under an account: the new repository records it at once.
    static void recordClone(Settings s, Path cwd, String url, String target, Run run) {
        if (run.account() == null) return;
        var name = target != null ? target : url.replaceAll("[/\\\\]+$", "").replaceAll("\\.git$", "").replaceAll(".*[/:]", "");
        var dir = cwd.resolve(name);
        if (Files.isDirectory(dir.resolve(".git"))) RepoRecord.write(s.getBinaries().getGit(), dir, s, run.account(), run.anonymous(), "cloned from " + url);
    }

    private Choose() {}
}
