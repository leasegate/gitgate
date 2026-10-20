package leasegate.gitgate.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/// Which account a git command in a folder runs as, and with which identity -- decided from four
/// sources: what the repository itself has recorded, the folder rules, the GitHub organisation its
/// remotes point at, and the account an alias (`gitwork`, `githome`) is pinned to. When they agree the
/// answer is automatic, and an unrecorded repository gets it written into its own config, so that
/// every git on the machine (an IDE, Git Bash's own) commits as the right person from then on. When
/// they disagree, or nothing names the repository at all, gitgate does not guess: a human decides.
/// A commit made under the wrong identity and then pushed under another account is a leak that a
/// force-push cannot undo.
public final class RepoDecision {

    public sealed interface Result permits Decided, Ask, Refuse, Plain {}

    /// `record` true: the repository has no record yet, or its identity differs; write it.
    public record Decided(Account account, boolean anonymous, String how, boolean record) implements Result {}

    /// A human must decide. `suggested` is the account the sources point at, when one does.
    public record Ask(String why, List<String> sources, String suggested) implements Result {}

    /// A contradiction that only a deliberate change of the record can settle.
    public record Refuse(String message) implements Result {}

    /// Not a repository, and no rule names the folder: plain git, as before.
    public record Plain(String why) implements Result {}

    /// The git subcommands that write history, or talk to a remote under an account. Anything else
    /// (status, log, diff, show, config...) runs without anyone being asked.
    public static final java.util.Set<String> NEEDS_ACCOUNT = java.util.Set.of(
        "commit", "merge", "rebase", "cherry-pick", "revert", "am", "tag", "pull", "push", "fetch",
        "clone", "ls-remote", "submodule", "stash", "notes", "commit-tree");

    /// `cloneUrl` is the URL of a `git clone` (the repository does not exist yet), else null.
    public static Result resolve(Settings s, Path dir, String pinned, String cloneUrl) {
        var repo = cloneUrl == null ? RepoConfig.find(dir) : Optional.<RepoConfig>empty();
        var sources = new LinkedHashMap<String, String>();   // "folder rule C:\x" -> account id

        var pinnedAccount = pinned == null ? null : s.byId(pinned).orElse(null);
        if (pinned != null && pinnedAccount == null) return new Refuse("this alias is for account " + pinned + ", which is not in " + Places.settings());
        AccountMatcher.match(s, dir).ifPresent(m -> sources.put("folder rule " + m.rule(), m.account().getId()));
        var urls = new ArrayList<String>();
        if (cloneUrl != null) urls.add(cloneUrl);
        repo.ifPresent(r -> urls.addAll(r.remotes().values()));
        for (var url : urls)
            RepoConfig.orgOf(url).ifPresent(org -> s.byOrg(org).ifPresent(a -> sources.put("GitHub org " + org, a.getId())));

        if (repo.isPresent() && repo.get().account() != null) {
            var r = repo.get();
            var recorded = s.byId(r.account()).orElse(null);
            if (recorded == null) return new Ask("this repository is recorded as account " + r.account() + ", which is not in the settings any more", list(sources), null);
            if (pinnedAccount != null && !pinnedAccount.getId().equalsIgnoreCase(recorded.getId()))
                return new Refuse("this repository is recorded as account " + recorded.getId() + ", and this alias would run as " + pinnedAccount.getId()
                    + ". Use plain git, or, if the record is wrong, change it: gitgate assign " + pinnedAccount.getId());
            var against = sources.entrySet().stream().filter(e -> !e.getValue().equalsIgnoreCase(recorded.getId())).map(e -> e.getKey() + " says " + e.getValue()).toList();
            if (!against.isEmpty())
                return new Ask("this repository is recorded as account " + recorded.getId() + ", but " + String.join(", ", against), list(sources), recorded.getId());
            var want = s.identity(recorded, r.anonymous());
            var stale = !s.shared(r.userEmail()) && (!want.name().equals(r.userName()) || !want.email().equalsIgnoreCase(String.valueOf(r.userEmail())));
            return new Decided(recorded, r.anonymous(), "recorded in the repository", stale);
        }

        var ids = new java.util.LinkedHashSet<String>();
        sources.values().forEach(v -> ids.add(v.toLowerCase()));
        if (pinnedAccount != null) {
            var other = sources.entrySet().stream().filter(e -> !e.getValue().equalsIgnoreCase(pinnedAccount.getId())).map(e -> e.getKey() + " says " + e.getValue()).toList();
            if (!other.isEmpty())
                return new Refuse("this alias would run as " + pinnedAccount.getId() + ", but " + String.join(", ", other)
                    + ". Use plain git, or record the repository deliberately: gitgate assign <account>");
            return new Decided(pinnedAccount, false, "alias", repo.isPresent());
        }
        if (ids.size() > 1) return new Ask("the rules disagree: " + String.join(", ", list(sources)), list(sources), null);
        if (ids.size() == 1) {
            var a = s.byId(ids.iterator().next()).orElseThrow();
            return new Decided(a, false, String.join(" and ", sources.keySet()), repo.isPresent());
        }
        if (repo.isPresent()) return new Ask("no rule names this repository: no folder rule, and no remote in a known GitHub organisation", List.of(), null);
        return new Plain("not a repository, and no rule names this folder");
    }

    static List<String> list(LinkedHashMap<String, String> sources) {
        return sources.entrySet().stream().map(e -> e.getKey() + " -> " + e.getValue()).toList();
    }

    private RepoDecision() {}
}
