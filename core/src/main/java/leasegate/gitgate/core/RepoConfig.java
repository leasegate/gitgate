package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/// What a repository itself says, read straight from its own config file -- no git process, so it
/// costs nothing on every command: the account gitgate recorded for it (`gitgate.account`), whether
/// its commits are anonymous (`gitgate.identity = anonymous`), whether it may push files the settings
/// otherwise refuse (`gitgate.allowPaths = true`), the identity set in it, and where its
/// remotes point. Found by walking up from a folder to the `.git` folder (or the `.git` file of a
/// worktree or submodule, which names the real one).
public record RepoConfig(Path workTree, Path configFile, String account, boolean anonymous,
                         String userName, String userEmail, Map<String, String> remotes, boolean allowPaths) {

    static final Pattern SECTION = Pattern.compile("^\\s*\\[\\s*([A-Za-z0-9.-]+)(?:\\s+\"(.*)\")?\\s*]\\s*$");
    static final Pattern GITHUB_ORG = Pattern.compile("github\\.com[^:/]*[:/]+([A-Za-z0-9_.-]+)/", Pattern.CASE_INSENSITIVE);

    public static Optional<RepoConfig> find(Path start) {
        for (var d = start.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            var dotGit = d.resolve(".git");
            if (Files.isDirectory(dotGit)) return Optional.of(read(d, configOf(dotGit)));
            if (Files.isRegularFile(dotGit)) {
                var gitDir = linkedGitDir(d, dotGit);
                if (gitDir != null) return Optional.of(read(d, configOf(gitDir)));
            }
        }
        return Optional.empty();
    }

    /// A `.git` file says `gitdir: <path>`; a worktree's git dir also has `commondir`, pointing at the
    /// repository whose config it shares.
    static Path linkedGitDir(Path workTree, Path dotGitFile) {
        try {
            var line = Files.readString(dotGitFile).trim();
            if (!line.startsWith("gitdir:")) return null;
            var p = Path.of(line.substring(7).trim());
            return p.isAbsolute() ? p : workTree.resolve(p).normalize();
        } catch (Exception e) { return null; }
    }

    static Path configOf(Path gitDir) {
        try {
            var common = gitDir.resolve("commondir");
            if (Files.isRegularFile(common)) {
                var c = Path.of(Files.readString(common).trim());
                return (c.isAbsolute() ? c : gitDir.resolve(c)).normalize().resolve("config");
            }
        } catch (Exception ignored) {}
        return gitDir.resolve("config");
    }

    static RepoConfig read(Path workTree, Path config) {
        String account = null, identity = null, userName = null, userEmail = null, allowPaths = null;
        var remotes = new LinkedHashMap<String, String>();
        try {
            if (Files.isRegularFile(config)) {
                String section = "", sub = null;
                for (var raw : Files.readAllLines(config)) {
                    var line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                    var m = SECTION.matcher(line);
                    if (m.matches()) { section = m.group(1).toLowerCase(Locale.ROOT); sub = m.group(2); continue; }
                    var eq = line.indexOf('=');
                    if (eq < 0) continue;
                    var key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
                    var value = unquote(line.substring(eq + 1).strip());
                    switch (section + "." + key) {
                        case "gitgate.account" -> account = value;
                        case "gitgate.identity" -> identity = value;
                        case "gitgate.allowpaths" -> allowPaths = value;
                        case "user.name" -> userName = value;
                        case "user.email" -> userEmail = value;
                        case "remote.url" -> { if (sub != null) remotes.put(sub, value); }
                        default -> {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return new RepoConfig(workTree, config, account, "anonymous".equalsIgnoreCase(identity), userName, userEmail, remotes, "true".equalsIgnoreCase(allowPaths));
    }

    static String unquote(String v) {
        var c = v.indexOf(" #");
        if (c >= 0 && !v.startsWith("\"")) v = v.substring(0, c).strip();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    /// The GitHub organisation (or user) a remote URL points at, in every form git accepts:
    /// `git@github.com:org/r.git`, `ssh://git@github.com/org/r`, `https://github.com/org/r`, and a
    /// host alias such as `github.com-work:org/r`.
    public static Optional<String> orgOf(String url) {
        if (url == null) return Optional.empty();
        var m = GITHUB_ORG.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
