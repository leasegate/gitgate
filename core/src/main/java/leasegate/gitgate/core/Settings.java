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
    List<Account> account = List.of();

    public Optional<Account> byId(String id) {
        return account.stream().filter(a -> a.getId().equalsIgnoreCase(id)).findFirst();
    }
}
