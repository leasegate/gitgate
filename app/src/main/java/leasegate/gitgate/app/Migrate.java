package leasegate.gitgate.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

import leasegate.gitgate.core.Places;

/// `gitgate migrate`: writes `settings.toml` from the two settings files of the tools gitgate
/// replaces -- the git/gh proxy's (binaries, paths, identities) and the repository wizard's (orgs,
/// gitignore defaults) -- matched by account name. No token and no key goes into the new file:
/// those are imported into the vault from the tray. Refuses to overwrite an existing file.
final class Migrate {

    static final Path HOME = Path.of(System.getProperty("user.home"), "xyz-jphil");
    static final Path PROXY = HOME.resolve("git_cli_proxy_with_creds").resolve("settings.xml");
    static final Path WIZARD = HOME.resolve("ggma").resolve("settings.xml");

    static int run() {
        var target = Places.settings();
        if (Files.exists(target)) { System.err.println("gitgate: " + target + " exists already; not overwriting it"); return 1; }
        try {
            var proxy = Files.exists(PROXY) ? xml(PROXY) : null;
            var wizard = Files.exists(WIZARD) ? xml(WIZARD) : null;
            if (proxy == null && wizard == null) { System.err.println("gitgate: found neither " + PROXY + " nor " + WIZARD); return 1; }
            var out = new ArrayList<String>();
            out.add("# gitgate settings, made by `gitgate migrate`. No secrets here: GitHub tokens and SSH keys live in the");
            out.add("# gitgate tray's vault. Windows paths go in 'single quotes' (no escaping); ids are what the aliases use.");
            out.add("");
            out.add("[binaries]");
            out.add("git = " + q(proxy == null ? "git" : proxy.select("binaries > git").attr("path")));
            out.add("gh = " + q(proxy == null ? "gh" : proxy.select("binaries > gh").attr("path")));
            out.add("ssh = 'C:/Windows/System32/OpenSSH/ssh.exe'");
            out.add("");
            out.add("[output]");
            out.add("# stderr keeps the ACCOUNT(..) line out of git's own output, which scripts read; stdout or none also work");
            out.add("accountLine = 'stderr'");
            out.add("noArgs = 'stdout'");
            if (wizard != null) {
                var root = wizard.selectFirst("settings");
                out.add("");
                out.add("[newRepo]");
                out.add("defaultAccount = " + q(root.attr("defaultAccountId")));
                out.add("gitignore = " + q(root.attr("defaultGitignoreType")));
                out.add("gitignoreTemplates = " + q(root.attr("gitignoreTemplatesPath")));
            }
            for (var a : accounts(proxy, wizard).values()) out.addAll(a);
            Files.createDirectories(target.getParent());
            Files.writeString(target, String.join("\n", out) + "\n");
            System.out.println("wrote " + target + " -- read it, then start gitgate and import the old credentials from its tray menu");
            return 0;
        } catch (Exception e) { System.err.println("gitgate: migrate failed: " + e.getMessage()); return 1; }
    }

    /// One block per account, keyed by name: the wizard's id (the one the aliases are named after)
    /// and orgs, the proxy's paths and identity.
    static Map<String, List<String>> accounts(Document proxy, Document wizard) {
        var byName = new LinkedHashMap<String, List<String>>();
        if (wizard == null) return byName;
        for (var w : wizard.select("settings > account")) {
            var name = w.attr("name");
            var p = proxy == null ? null : proxy.select("accounts > account").stream().filter(x -> x.attr("name").equalsIgnoreCase(name)).findFirst().orElse(null);
            var l = new ArrayList<String>();
            l.add("");
            l.add("[[account]]");
            l.add("id = " + q(w.attr("id")));
            l.add("name = " + q(name));
            l.add("description = " + q(w.select("description").text()));
            if (p != null) {
                l.add("userName = " + q(p.select("git").attr("userName")));
                l.add("userEmail = " + q(p.select("git").attr("userEmail")));
            }
            l.add("orgs = " + list(w.select("org").eachText()));
            l.add("paths = " + list(p == null ? List.of() : p.select("activationRules > pathParent").eachText()));
            l.add("pathRegex = " + list(p == null ? List.of() : p.select("activationRules > pathRegex").eachText().stream().map(String::trim).toList()));
            l.add("# 'gate' = the key is held by the gitgate tray (import it there); a file path = the old way");
            l.add("ssh = 'gate'");
            l.add("# regular expressions that must never appear in anything pushed under this account");
            l.add("forbidden = []");
            byName.put(name, l);
        }
        return byName;
    }

    static Document xml(Path p) throws Exception { return Jsoup.parse(Files.readString(p), "", Parser.xmlParser()); }

    static String q(String s) { return s.contains("'") ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : "'" + s + "'"; }

    static String list(List<String> items) { return "[" + String.join(", ", items.stream().map(Migrate::q).toList()) + "]"; }

    private Migrate() {}
}
