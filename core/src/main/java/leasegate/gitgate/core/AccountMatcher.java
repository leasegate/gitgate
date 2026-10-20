package leasegate.gitgate.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/// Which account a directory belongs to: the first account with a `paths` entry that is the
/// directory or a parent of it, else the first whose `pathRegex` matches. Paths are compared without
/// case on Windows, with every slash style folded together (C:\x, C:/x, /c/x from Git Bash,
/// /mnt/c/x from WSL), and regexes see forward slashes.
public final class AccountMatcher {

    public record Match(Account account, String rule, String kind) {

        /// `ACCOUNT(iv,Ivan) ACTIVATEDBY(code-folder,path)`, the line a human or an agent reads to
        /// know which identity a command ran under.
        public String line() { return "ACCOUNT(%s,%s) ACTIVATEDBY(%s,%s)".formatted(account.getId(), account.getName(), rule, kind); }
    }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public static Optional<Match> match(Settings s, Path dir) {
        var here = normalize(dir.toAbsolutePath().toString());
        for (var a : s.getAccount()) {
            for (var p : a.getPaths()) {
                var parent = normalize(p);
                if (here.equals(parent) || here.startsWith(parent + "/")) return Optional.of(new Match(a, p, "path"));
            }
            for (var r : a.getPathRegex())
                if (Pattern.compile(r).matcher(here).matches()) return Optional.of(new Match(a, r, "pathRegex"));
        }
        return Optional.empty();
    }

    public static String noMatchLine(String tool) { return "ACCOUNT(NONE,plain " + tool + ") ACTIVATEDBY(no rules matched,fallback)"; }

    static String normalize(String path) {
        var n = path.replace('\\', '/');
        if (WINDOWS) {
            n = n.toLowerCase(Locale.ROOT);
            if (n.matches("^/mnt/[a-z]/.*")) n = n.charAt(5) + ":" + n.substring(6);
            else if (n.matches("^/[a-z]/.*")) n = n.charAt(1) + ":" + n.substring(2);
        }
        while (n.length() > 1 && n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    private AccountMatcher() {}
}
