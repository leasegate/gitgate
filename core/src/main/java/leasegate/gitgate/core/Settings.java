package leasegate.gitgate.core;

import java.util.List;
import java.util.Optional;

import datapotter.datahelper.Data;

/// `~/leasegate/gitgate/settings.toml`: everything gitgate needs except the secrets, which live in
/// the tray's vault and nowhere else.
@Data
public final class Settings extends Settings_A {
    Binaries binaries = new Binaries();
    Output output = new Output();
    NewRepo newRepo = new NewRepo();
    Anonymous anonymous = new Anonymous();
    /// Addresses any account's repositories may carry as author or committer besides the account's own
    /// (for example a coding agent that commits under its own name). A repository whose own identity
    /// is one of these keeps it. Empty unless the settings name some.
    List<String> sharedAuthors = List.of();
    /// The hosts gitgate's agent serves; an ssh to any other host gets no key from it.
    List<String> sshHosts = List.of("github.com");
    /// Patterns for git's global ignore file, which gitgate setup writes when this is not empty (and
    /// the user has no core.excludesFile of their own). Empty unless the settings name some.
    List<String> ignore = List.of();
    /// Regular expressions over file paths never pushed under any account (each account can add its
    /// own). A repository opts out with `git config gitgate.allowPaths true`; one push with
    /// GITGATE_ALLOW_PATHS=1. Empty unless the settings name some.
    List<String> forbiddenPaths = List.of();
    /// How the tray's windows are framed: 'glow' (default) or 'native'; `resizableWindows` the same
    /// for the resizable ones ('native' keeps Windows' snap and edge resizing).
    String windows = "glow";
    String resizableWindows = "glow";
    List<Account> account = List.of();

    public Optional<Account> byId(String id) {
        if (id == null) return Optional.empty();
        return account.stream().filter(a -> a.getId().equalsIgnoreCase(id)).findFirst();
    }

    /// The account whose `orgs` names this GitHub organisation.
    public Optional<Account> byOrg(String org) {
        if (org == null) return Optional.empty();
        return account.stream().filter(a -> a.getOrgs().stream().anyMatch(o -> o.equalsIgnoreCase(org))).findFirst();
    }

    /// Every address a commit in a repository of this account may carry.
    public java.util.Set<String> allowedEmails(Account a, boolean anonymous) {
        var out = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (anonymous) out.add(this.anonymous.getUserEmail()); else out.addAll(a.ownEmails());
        out.addAll(sharedAuthors);
        return out;
    }

    public boolean shared(String email) { return email != null && sharedAuthors.stream().anyMatch(e -> e.equalsIgnoreCase(email)); }

    /// Who commits in a repository of this account: its own identity, or the anonymous one.
    public Identity identity(Account a, boolean anonymous) {
        return anonymous ? new Identity(this.anonymous.getUserName(), this.anonymous.getUserEmail())
            : new Identity(a.getUserName(), a.getUserEmail());
    }
}
