package leasegate.gitgate.app;

import java.util.Optional;

import leasegate.gitgate.core.Places;
import leasegate.sshagent.VaultKeys;
import leasegate.vault.Vault;

/// What gitgate keeps in its vault. `ssh.<account>` is the account's SSH key (see VaultKeys, which
/// also writes the public half that ssh is pointed at); `gh.<account>.token` its everyday GitHub
/// token; `gh.<account>.admin` an optional second token, released only for ADMIN requests.
final class Secrets {

    final Vault vault;
    final VaultKeys keys;

    Secrets(Vault vault) {
        this.vault = vault;
        this.keys = new VaultKeys(vault, Places.publicKey("x").getParent());
    }

    static String token(String account, boolean admin) { return "gh." + account + (admin ? ".admin" : ".token"); }

    /// The account's ADMIN token when there is one and the request is ADMIN, else its everyday one.
    Optional<String> tokenFor(String account, boolean admin) {
        return admin ? vault.get(token(account, true)).or(() -> vault.get(token(account, false))) : vault.get(token(account, false));
    }
}
