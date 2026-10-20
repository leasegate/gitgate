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

    /// Who commits in a repository of this account: its own identity, or the anonymous one.
    public Identity identity(Account a, boolean anonymous) {
        return anonymous ? new Identity(this.anonymous.getUserName(), this.anonymous.getUserEmail())
            : new Identity(a.getUserName(), a.getUserEmail());
    }
}
