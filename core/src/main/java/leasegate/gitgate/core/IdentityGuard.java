package leasegate.gitgate.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Before a push, every commit that is not yet on a remote must carry the identity of the account
/// pushing it -- as author and as committer. A commit made as one account and pushed by another puts
/// the first account's name on the second's repository for good: a force-push deletes nothing.
/// Returns the commits that do not match; an empty list means go ahead.
public final class IdentityGuard {

    public record Stranger(String commit, String author, String committer) {}

    static final int MAX = 20;

    public static List<Stranger> scan(String git, Path repo, Set<String> allowedEmails) {
        var out = new ArrayList<Stranger>();
        try {
            var p = new ProcessBuilder(git, "log", "--format=%h%x09%an <%ae>%x09%ae%x09%cn <%ce>%x09%ce",
                "--branches", "--tags", "--not", "--remotes").directory(repo.toFile()).redirectErrorStream(true).start();
            try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    var f = line.split("\t", -1);
                    if (f.length < 5) continue;
                    if ((!allowed(allowedEmails, f[2]) || !allowed(allowedEmails, f[4])) && out.size() < MAX) out.add(new Stranger(f[0], f[1], f[3]));
                }
            }
            p.waitFor();
        } catch (Exception e) {
            return List.of(new Stranger("?", "(scan failed: " + e.getMessage() + ")", ""));
        }
        return out;
    }

    static boolean allowed(Set<String> allowed, String email) { return allowed.stream().anyMatch(a -> a.equalsIgnoreCase(email)); }

    private IdentityGuard() {}
}
