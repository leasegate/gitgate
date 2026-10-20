package leasegate.gitgate.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Before anything is pushed under an account that names `forbidden` terms, every commit that is
/// not yet on a remote -- its message and its patch -- is searched for them. A push to a public
/// repository cannot be taken back (a force-push deletes nothing), so the time to catch a word that
/// must not be there is before it leaves. Returns the hits; an empty list means go ahead.
public final class PushGuard {

    public record Hit(String commit, String term, String line) {}

    static final int MAX_HITS = 20;

    public static List<Hit> scan(String git, Path repo, Account account) {
        if (account.getForbidden().isEmpty()) return List.of();
        var patterns = account.getForbidden().stream().map(t -> Pattern.compile(t, Pattern.CASE_INSENSITIVE)).toList();
        var hits = new ArrayList<Hit>();
        try {
            var p = new ProcessBuilder(git, "-c", "core.quotepath=off", "log", "-p", "--no-color", "--format=commit %h%n%B",
                "--branches", "--tags", "--not", "--remotes").directory(repo.toFile()).redirectErrorStream(true).start();
            try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String commit = "?", line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("commit ") && line.length() < 50) { commit = line.substring(7).trim(); continue; }
                    if (line.startsWith("-") && !line.startsWith("---")) continue;
                    for (var pat : patterns) {
                        var m = pat.matcher(line);
                        if (m.find() && hits.size() < MAX_HITS) hits.add(new Hit(commit, m.group(), line.length() > 160 ? line.substring(0, 160) + "..." : line));
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            return List.of(new Hit("?", "(scan failed)", "the push guard could not read the commits: " + e.getMessage()));
        }
        return hits;
    }

    /// Files added or changed in commits not yet on a remote whose path matches one of the account's
    /// `forbiddenPaths`.
    public static List<String> paths(String git, Path repo, Account account) {
        if (account.getForbiddenPaths().isEmpty()) return List.of();
        var patterns = account.getForbiddenPaths().stream().map(Pattern::compile).toList();
        var hits = new java.util.TreeSet<String>();
        try {
            var p = new ProcessBuilder(git, "-c", "core.quotepath=off", "log", "--name-only", "--diff-filter=AM", "--format=",
                "--branches", "--tags", "--not", "--remotes").directory(repo.toFile()).redirectErrorStream(true).start();
            try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    var path = line.strip();
                    if (!path.isEmpty() && patterns.stream().anyMatch(x -> x.matcher(path).find())) hits.add(path);
                }
            }
            p.waitFor();
        } catch (Exception e) { return List.of("(the path check failed: " + e.getMessage() + ")"); }
        return List.copyOf(hits);
    }

    private PushGuard() {}
}
