package leasegate.gitgate.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import leasegate.gitgate.core.Settings;

/// Finds the credentials the tools before gitgate kept in the open, so they can be moved into the
/// vault once: tokens in the old proxy's settings.xml and in `GH<ID>_GITHUB_TOKEN` environment
/// variables, and SSH key files. Only reads; removing the old copies is left to the human, because
/// deleting a private key file cannot be undone. Old accounts are matched to gitgate's by name.
final class OldCredentials {

    static final Path OLD_PROXY = Path.of(System.getProperty("user.home"), "xyz-jphil", "git_cli_proxy_with_creds", "settings.xml");

    static List<OldCredential> find(Settings s) {
        var out = new ArrayList<OldCredential>();
        var byName = new LinkedHashMap<String, String>();
        s.getAccount().forEach(a -> byName.put(a.getName().toLowerCase(), a.getId()));
        if (Files.exists(OLD_PROXY)) {
            try {
                var doc = Jsoup.parse(Files.readString(OLD_PROXY), "", Parser.xmlParser());
                for (var acc : doc.select("account")) {
                    var id = byName.getOrDefault(acc.attr("name").toLowerCase(), byName.get(acc.attr("id").toLowerCase()));
                    if (id == null) id = s.byId(acc.attr("id")).map(a -> a.getId()).orElse(null);
                    if (id == null) continue;
                    var token = acc.select("gh").attr("token");
                    var key = acc.select("git").attr("sshKey");
                    out.add(new OldCredential(id, OLD_PROXY.toString(), token.isBlank() ? null : token, key.isBlank() ? null : Path.of(key)));
                }
            } catch (Exception e) { System.err.println("gitgate: cannot read " + OLD_PROXY + ": " + e.getMessage()); }
        }
        for (var a : s.getAccount()) {
            var name = "GH" + a.getId().toUpperCase() + "_GITHUB_TOKEN";
            var v = userVariable(name);
            if (v != null && !v.isBlank()) out.add(new OldCredential(a.getId(), "environment variable " + name, v.trim(), null));
        }
        return out;
    }

    /// The process environment first; a variable set after this process started is only in the
    /// registry, so that is asked next.
    static String userVariable(String name) {
        var v = System.getenv(name);
        if (v != null) return v;
        try {
            var p = new ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", name).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for (var line : out.split("\\R")) {
                var f = line.trim().split("\\s+", 3);
                if (f.length == 3 && f[0].equalsIgnoreCase(name)) return f[2];
            }
        } catch (Exception ignored) {}
        return null;
    }

    private OldCredentials() {}
}
