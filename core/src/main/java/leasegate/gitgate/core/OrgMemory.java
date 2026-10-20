package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/// GitHub organisations no account's `orgs` names, with the account the human chose for them when
/// something was first pushed there (`~/leasegate/gitgate/orgs.json`). settings.toml stays the
/// human's file; this is what was answered in a window.
public final class OrgMemory {

    static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static Path file() { return Places.home().resolve("orgs.json"); }

    /// The account for an organisation: from the settings first, then from what was answered before.
    public static Optional<Account> account(Settings s, String org) {
        if (org == null) return Optional.empty();
        var fromSettings = s.byOrg(org);
        if (fromSettings.isPresent()) return fromSettings;
        return s.byId(all().get(org.toLowerCase()));
    }

    public static synchronized Map<String, String> all() {
        try { return Files.exists(file()) ? new TreeMap<>(M.readValue(file().toFile(), new TypeReference<Map<String, String>>() {})) : new TreeMap<>(); }
        catch (Exception e) { return new TreeMap<>(); }
    }

    public static synchronized void remember(String org, String account) {
        try {
            var all = all();
            all.put(org.toLowerCase(), account);
            Files.createDirectories(file().getParent());
            var tmp = file().resolveSibling("orgs.json.tmp");
            M.writeValue(tmp.toFile(), all);
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { System.err.println("gitgate: could not update " + file() + ": " + e.getMessage()); }
    }

    private OrgMemory() {}
}
