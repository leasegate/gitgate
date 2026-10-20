package leasegate.gitgate.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// What has already happened: for every repository under a folder, the commits whose author or
/// committer carries ANOTHER of the configured accounts' addresses -- one identity's name on the
/// other's repository. Other people's commits (an upstream merged into a fork) are not findings.
/// Split into those already on a remote (public, if the repository is) and those still only here
/// (fixable by a rewrite before the next push). Read-only: it runs `git log` and nothing else.
public final class Audit {

    public record Finding(Path repo, String account, String how, int pushed, int local, List<String> examples) {}

    static final Set<String> SKIP = Set.of("target", "node_modules", "build", "out", ".gradle", ".idea");

    public static List<Path> repos(Path base, int depth) throws IOException {
        var out = new ArrayList<Path>();
        Files.walkFileTree(base, Set.of(), depth, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                var name = d.getFileName() == null ? "" : d.getFileName().toString();
                if (SKIP.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                if (Files.exists(d.resolve(".git"))) { out.add(d); return FileVisitResult.SKIP_SUBTREE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        return out;
    }

    /// null when the repository belongs to no account (nothing to compare against).
    public static Finding check(Settings s, Path repo) {
        var git = s.getBinaries().getGit();
        Account account;
        boolean anonymous = false;
        String how;
        switch (RepoDecision.resolve(s, repo, null, null)) {
            case RepoDecision.Decided d -> { account = d.account(); anonymous = d.anonymous(); how = d.how(); }
            case RepoDecision.Ask a when a.suggested() != null -> { account = s.byId(a.suggested()).orElseThrow(); how = "recorded, but " + a.why(); }
            default -> { return null; }
        }
        var foreign = new HashSet<String>();
        for (var other : s.getAccount()) if (!other.getId().equalsIgnoreCase(account.getId())) other.ownEmails().forEach(e -> foreign.add(e.toLowerCase()));
        if (anonymous) account.ownEmails().forEach(e -> foreign.add(e.toLowerCase()));
        var local = commits(git, repo, "--branches", "--tags", "--not", "--remotes");
        var pushed = commits(git, repo, "--remotes");
        int nPushed = 0, nLocal = 0;
        var examples = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var c : local) if (seen.add(c[0]) && leak(foreign, c)) { nLocal++; if (examples.size() < 3) examples.add("local  " + c[0] + "  " + c[1]); }
        for (var c : pushed) if (seen.add(c[0]) && leak(foreign, c)) { nPushed++; if (examples.size() < 3) examples.add("pushed " + c[0] + "  " + c[1]); }
        return new Finding(repo, account.getId() + (anonymous ? " (anonymous)" : ""), how, nPushed, nLocal, examples);
    }

    static boolean leak(Set<String> foreign, String[] c) {
        return foreign.contains(c[2].toLowerCase()) || foreign.contains(c[3].toLowerCase());
    }

    /// hash, "author <email>", author email, committer email
    static List<String[]> commits(String git, Path repo, String... range) {
        var out = new ArrayList<String[]>();
        try {
            var cmd = new ArrayList<>(List.of(git, "log", "--format=%h%x09%an <%ae>%x09%ae%x09%ce"));
            cmd.addAll(List.of(range));
            var p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
            for (var line : new String(p.getInputStream().readAllBytes()).split("\\R")) {
                var f = line.split("\t", -1);
                if (f.length == 4) out.add(f);
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return out;
    }

    private Audit() {}
}
