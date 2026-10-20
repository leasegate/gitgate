package leasegate.gitgate.core;

import leasegate.core.access.Scope;

/// What a git or gh request reaches: one account, up to one tier. `detail` -- the repository, the
/// gh verb -- is shown to the human but is not part of the match, so one approval to push as an
/// account covers every repository of that account until it ends. `account` null means any.
public record GitScope(String account, Tier tier, String detail, Integer requestedMinutes) implements Scope<GitScope> {

    @Override public boolean covers(GitScope r) {
        return (account == null || account.equalsIgnoreCase(r.account)) && tier.ordinal() >= r.tier.ordinal();
    }

    @Override public GitScope narrowedTo(GitScope r) {
        return new GitScope(r.account != null ? r.account : account, r.tier, r.detail, r.requestedMinutes);
    }

    /// One for naming the account, one for asking only to read.
    @Override public int narrowness() { return (account != null ? 1 : 0) + (tier == Tier.READ ? 1 : 0); }
}
