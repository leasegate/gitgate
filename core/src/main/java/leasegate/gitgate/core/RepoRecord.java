package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/// Writing a decision down. In the repository itself -- `gitgate.account`, `gitgate.identity` and
/// the matching `user.name` / `user.email` in its own config, so the decision travels with the
/// folder and every git commits correctly, not only gitgate's -- and in `repos.json`, the list the
/// tray's "Repositories and rules" window shows. The repository's own config is the truth; the
/// list is only an index of what gitgate has seen.
public final class RepoRecord {

    public record Seen(String account, boolean anonymous, String how, String at) {}

    static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void write(String git, Path workTree, Settings s, Account a, boolean anonymous, String how) {
        var id = s.identity(a, anonymous);
        config(git, workTree, "gitgate.account", a.getId());
        config(git, workTree, "gitgate.identity", anonymous ? "anonymous" : "account");
        // a repository set to commit as a shared author (a coding agent) keeps that identity, unless it is made anonymous
        var own = RepoConfig.find(workTree).map(RepoConfig::userEmail).orElse(null);
        if (!anonymous && s.shared(own)) { remember(workTree, new Seen(a.getId(), anonymous, how, Instant.now().toString())); return; }
        config(git, workTree, "user.name", id.name());
        config(git, workTree, "user.email", id.email());
        remember(workTree, new Seen(a.getId(), anonymous, how, Instant.now().toString()));
    }

    static void config(String git, Path workTree, String key, String value) {
        try {
            var p = new ProcessBuilder(git, "-C", workTree.toString(), "config", "--local", key, value).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IllegalStateException("git config " + key + " failed in " + workTree + ": " + out.trim());
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("cannot run git config in " + workTree + ": " + e.getMessage()); }
    }

    public static Path list() { return Places.home().resolve("repos.json"); }

    public static synchronized Map<String, Seen> seen() {
        try {
            if (!Files.exists(list())) return new TreeMap<>();
            return new TreeMap<>(M.readValue(list().toFile(), new TypeReference<Map<String, Seen>>() {}));
        } catch (Exception e) { return new TreeMap<>(); }
    }

    /// Best effort: a list that cannot be written must not stop a commit.
    static synchronized void remember(Path workTree, Seen seen) {
        try {
            var all = seen();
            all.put(workTree.toAbsolutePath().normalize().toString(), seen);
            Files.createDirectories(list().getParent());
            var tmp = list().resolveSibling("repos.json.tmp");
            M.writeValue(tmp.toFile(), all);
            Files.move(tmp, list(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { System.err.println("gitgate: could not update " + list() + ": " + e.getMessage()); }
    }

    /// The last few authors of a repository, for the human deciding which account it is.
    public static List<String> recentAuthors(String git, Path workTree, int n) {
        var out = new ArrayList<String>();
        try {
            var p = new ProcessBuilder(git, "-C", workTree.toString(), "log", "-" + n * 5, "--format=%an <%ae>").redirectErrorStream(true).start();
            for (var line : new String(p.getInputStream().readAllBytes()).split("\\R"))
                if (!line.isBlank() && !line.startsWith("fatal") && !out.contains(line) && out.size() < n) out.add(line);
            p.waitFor();
        } catch (Exception ignored) {}
        return out;
    }

    private RepoRecord() {}
}
