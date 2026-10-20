package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Makes the official git and ssh work through gitgate with no program of gitgate's in between.
/// gitgate keeps its git settings in a file of its own, which `~/.gitconfig` includes with one
/// line at its end:
///
/// - per account, `includeIf "hasconfig:remote.*.url:..."` for each of its GitHub organisations and
///   `includeIf "gitdir/i:..."` for each of its folders, pointing at a small file with the
///   account's identity: git itself picks the identity, before any record exists;
/// - `core.sshCommand` = Windows' OpenSSH, which reaches gitgate's agent on its named pipe (git
///   runs a bare path without a shell);
/// - `core.hooksPath` = gitgate's hooks: pre-commit decides and records an unrecorded repository,
///   pre-push runs the identity and forbidden-terms checks. Each then runs the repository's own
///   hook of that name, if it has one.
///
/// gitgate's ssh settings (`Host github.com`, `IdentityAgent` = the agent pipe) are its own file too,
/// pulled into `~/.ssh/config` by one `Include` line at its top. Both files of gitgate's are
/// rewritten from settings.toml whenever it changes; the user's own entries in ~/.gitconfig and
/// ~/.ssh/config are never touched. `undo` removes the two lines.
public final class GitSetup {

    static final String SSH_MARK = "# gitgate: github.com through the gitgate agent (gitgate setup --undo removes this line)";

    public static Path dir() { return Places.home().resolve("git"); }
    public static Path config() { return dir().resolve("gitgate.gitconfig"); }
    public static Path hooks() { return dir().resolve("hooks"); }
    public static Path sshInclude() { return dir().resolve("ssh_config"); }
    public static Path ignoreFile() { return dir().resolve("ignore"); }
    public static Path sshConfig() { return Path.of(System.getProperty("user.home"), ".ssh", "config"); }

    /// Writes gitgate's own files from the settings: safe to run again whenever settings.toml changes.
    public static List<String> writeFiles(Settings s, String gitgateExe) throws Exception {
        var done = new ArrayList<String>();
        Files.createDirectories(hooks());
        var cfg = new StringBuilder("# Written by gitgate from " + Places.settings() + " -- edit that, not this.\n");
        cfg.append("[core]\n\tsshCommand = ").append(s.getBinaries().getSsh().replace('\\', '/')).append('\n');
        cfg.append("\thooksPath = ").append(slash(hooks())).append('\n');
        // git reads one global ignore file. When the settings list `ignore` patterns, gitgate writes
        // one -- unless the user already names a file of their own, which then stays in charge.
        var userExcludes = run(s.getBinaries().getGit(), "config", "--global", "--get", "core.excludesFile").trim();
        if (!s.getIgnore().isEmpty()) {
            if (userExcludes.isEmpty() || userExcludes.equalsIgnoreCase(slash(ignoreFile()))) {
                Files.writeString(ignoreFile(), "# Written by gitgate from the `ignore` list in " + Places.settings() + " -- edit that, not this.\n" + String.join("\n", s.getIgnore()) + "\n");
                cfg.append("\texcludesFile = ").append(slash(ignoreFile())).append('\n');
                done.add(ignoreFile().toString());
            } else done.add("your own core.excludesFile (" + userExcludes + ") stays in charge; add " + s.getIgnore() + " to it yourself");
        }
        for (var a : s.getAccount()) {
            var file = dir().resolve("account-" + a.getId() + ".gitconfig");
            var id = s.identity(a, false);
            Files.writeString(file, "[user]\n\tname = " + id.name() + "\n\temail = " + id.email() + "\n");
            for (var org : a.getOrgs())
                for (var url : List.of("git@github.com:" + org + "/**", "https://github.com/" + org + "/**", "ssh://git@github.com/" + org + "/**"))
                    cfg.append("[includeIf \"hasconfig:remote.*.url:").append(url).append("\"]\n\tpath = ").append(slash(file)).append('\n');
            for (var p : a.getPaths())
                cfg.append("[includeIf \"gitdir/i:").append(p.replace('\\', '/')).append(p.endsWith("\\") || p.endsWith("/") ? "" : "/").append("\"]\n\tpath = ").append(slash(file)).append('\n');
        }
        Files.writeString(config(), cfg.toString());
        done.add(config().toString());
        Files.writeString(sshInclude(), "# Written by gitgate -- edit " + Places.settings() + ", not this.\nHost github.com\n    IdentityAgent " + Places.agentForSsh() + "\n");
        done.add(sshInclude().toString());
        var exe = gitgateExe.replace('\\', '/');
        Files.writeString(hooks().resolve("pre-commit"), String.join("\n",
            "#!/bin/sh",
            "# gitgate: an unrecorded repository is decided and recorded first; then the repository's own hook.",
            "if [ -z \"$(git config --local gitgate.account)\" ]; then \"" + exe + "\" hook pre-commit || exit 1; fi",
            "own=\"$(git rev-parse --git-common-dir)/hooks/pre-commit\"",
            "if [ -x \"$own\" ]; then exec \"$own\" \"$@\"; fi",
            "exit 0", ""));
        Files.writeString(hooks().resolve("pre-push"), String.join("\n",
            "#!/bin/sh",
            "# gitgate: every commit about to leave must carry this repository's identity and no forbidden term; then the repository's own hook.",
            "refs=$(cat)",
            "printf '%s\\n' \"$refs\" | \"" + exe + "\" hook pre-push \"$@\" || exit 1",
            "own=\"$(git rev-parse --git-common-dir)/hooks/pre-push\"",
            "if [ -x \"$own\" ]; then printf '%s\\n' \"$refs\" | \"$own\" \"$@\"; exit $?; fi",
            "exit 0", ""));
        done.add(hooks().toString());
        return done;
    }

    /// Writes the files, then the one include line in ~/.gitconfig and the github.com block in ~/.ssh/config.
    public static List<String> install(Settings s, String gitgateExe) throws Exception {
        var done = writeFiles(s, gitgateExe);
        var git = s.getBinaries().getGit();
        var hooksNow = run(git, "config", "--global", "--get", "core.hooksPath").trim();
        if (!hooksNow.isEmpty() && !hooksNow.equalsIgnoreCase(slash(hooks())))
            throw new IllegalStateException("~/.gitconfig already sets core.hooksPath = " + hooksNow + "; gitgate's hooks would replace it. Remove it first.");
        var includes = run(git, "config", "--global", "--get-all", "include.path");
        if (!includes.contains(slash(config()))) { run(git, "config", "--global", "--add", "include.path", slash(config())); done.add("~/.gitconfig: include.path " + slash(config())); }
        var ssh = sshConfig();
        var text = Files.exists(ssh) ? Files.readString(ssh) : "";
        if (!text.contains(SSH_MARK)) {
            Files.createDirectories(ssh.getParent());
            if (Files.exists(ssh)) Files.copy(ssh, ssh.resolveSibling("config.before-gitgate"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(ssh, includeLines() + "\n" + text);
            done.add(ssh + ": Include " + slash(sshInclude()) + " (backup config.before-gitgate)");
        }
        return done;
    }

    public static List<String> undo(Settings s) throws Exception {
        var done = new ArrayList<String>();
        var git = s.getBinaries().getGit();
        if (run(git, "config", "--global", "--get-all", "include.path").contains(slash(config()))) {
            run(git, "config", "--global", "--unset", "--fixed-value", "include.path", slash(config()));
            done.add("~/.gitconfig: include line removed");
        }
        var ssh = sshConfig();
        if (Files.exists(ssh)) {
            var text = Files.readString(ssh);
            var line = includeLines();
            if (text.contains(line)) {
                Files.writeString(ssh, text.replace(line + "\n", "").replace(line, ""));
                done.add(ssh + ": Include line removed");
            }
        }
        return done;
    }

    /// The two lines gitgate puts at the top of ~/.ssh/config: its mark, and the Include.
    static String includeLines() { return SSH_MARK + "\nInclude " + slash(sshInclude()) + "\n"; }

    public static boolean installed(Settings s) {
        try { return run(s.getBinaries().getGit(), "config", "--global", "--get-all", "include.path").contains(slash(config())); }
        catch (Exception e) { return false; }
    }

    static String slash(Path p) { return p.toAbsolutePath().toString().replace('\\', '/'); }

    static String run(String... cmd) throws Exception {
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    private GitSetup() {}
}
