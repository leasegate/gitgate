package leasegate.gitgate.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import leasegate.gitgate.core.*;

/// `git` with the right identity for the directory it runs in. Under an account whose key is in the
/// gate, the real git is pointed at Windows' OpenSSH with the account's PUBLIC key file and the
/// gate's agent pipe, so the private key is never anywhere ssh could read it; every signature is
/// approved by the gate. A push is first searched for the account's forbidden terms.
final class GitRun {

    static final Set<String> REMOTE = Set.of("push", "pull", "fetch", "clone", "ls-remote", "remote", "submodule");
    static final String ALLOW_FORBIDDEN = "--gitgate-allow-forbidden";

    static int proxy(List<String> args, String pinned) {
        var s = SettingsStore.load();
        var match = resolve(s, pinned);
        Notice.account(s, match.map(AccountMatcher.Match::line).orElse(AccountMatcher.noMatchLine("git")), args.isEmpty());
        if (match.isEmpty()) return exec(command(s, null, args), Map.of());
        var a = match.get().account();
        var allow = args.contains(ALLOW_FORBIDDEN);
        var rest = args.stream().filter(x -> !x.equals(ALLOW_FORBIDDEN)).toList();
        var verb = verb(rest);
        if (a.viaGate() && REMOTE.contains(verb)) {
            if (!Files.exists(Places.publicKey(a.getId())))
                throw new IllegalStateException("account " + a.getId() + " has no SSH key in gitgate yet: open the gitgate tray menu, Import or Make an SSH key");
            if (!Gate.running()) throw new IllegalStateException("gitgate is not running, and it holds account " + a.getId() + "'s SSH key: start gitgate.exe (it lives in the tray)");
        }
        if (verb.equals("push")) {
            var hits = PushGuard.scan(s.getBinaries().getGit(), repoDir(rest), a);
            if (!hits.isEmpty() && !allow) return refuse(a, hits);
            if (!hits.isEmpty()) System.err.println("gitgate: pushing despite " + hits.size() + " forbidden term(s), as " + ALLOW_FORBIDDEN + " asked");
        }
        return exec(command(s, a, rest), env(a));
    }

    static Optional<AccountMatcher.Match> resolve(Settings s, String pinned) {
        if (pinned == null) return AccountMatcher.match(s, Path.of(""));
        var a = s.byId(pinned).orElseThrow(() -> new IllegalStateException("this alias is for account " + pinned + ", which is not in " + Places.settings()));
        return Optional.of(new AccountMatcher.Match(a, "pinned", "alias"));
    }

    static List<String> command(Settings s, Account a, List<String> args) {
        var c = new ArrayList<String>();
        c.add(s.getBinaries().getGit());
        if (a != null) {
            c.add("-c");
            c.add("core.sshCommand=" + sshCommand(s, a));
            if (a.getUserName() != null) { c.add("-c"); c.add("user.name=" + a.getUserName()); }
            if (a.getUserEmail() != null) { c.add("-c"); c.add("user.email=" + a.getUserEmail()); }
        }
        c.addAll(args);
        return c;
    }

    /// Quotes escaped so they survive the shell git runs the command through.
    static String sshCommand(Settings s, Account a) {
        if (!a.viaGate()) return "ssh -i \\\"" + a.getSsh().replace('\\', '/') + "\\\"";
        return s.getBinaries().getSsh().replace('\\', '/') + " -o IdentityAgent=" + Places.agentForSsh() + " -o IdentitiesOnly=yes -i \\\""
            + Places.publicKey(a.getId()).toString().replace('\\', '/') + "\\\"";
    }

    static Map<String, String> env(Account a) { return a.viaGate() ? Map.of("SSH_AUTH_SOCK", Places.agentSocket()) : Map.of(); }

    /// The git subcommand: the first argument that is not an option (and not the value of -c or -C).
    static String verb(List<String> args) {
        for (var i = 0; i < args.size(); i++) {
            var x = args.get(i);
            if (x.equals("-c") || x.equals("-C")) { i++; continue; }
            if (!x.startsWith("-")) return x;
        }
        return "";
    }

    static Path repoDir(List<String> args) {
        for (var i = 0; i + 1 < args.size(); i++) if (args.get(i).equals("-C")) return Path.of(args.get(i + 1));
        return Path.of("").toAbsolutePath();
    }

    static int refuse(Account a, List<PushGuard.Hit> hits) {
        System.err.println("gitgate: push refused. These commits carry terms that account " + a.getId() + " must never publish:");
        hits.forEach(h -> System.err.println("  " + h.commit() + "  [" + h.term() + "]  " + h.line().trim()));
        System.err.println("Rewrite those commits (the text is still unpublished), or, if a hit is a false match, push with " + ALLOW_FORBIDDEN + ".");
        return 1;
    }

    static int exec(List<String> command, Map<String, String> env) {
        try {
            var pb = new ProcessBuilder(command).inheritIO();
            pb.environment().putAll(env);
            return pb.start().waitFor();
        } catch (Exception e) { throw new IllegalStateException("cannot run " + command.getFirst() + ": " + e.getMessage()); }
    }

    private GitRun() {}
}
