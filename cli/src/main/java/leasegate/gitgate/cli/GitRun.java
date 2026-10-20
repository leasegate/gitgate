package leasegate.gitgate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import leasegate.gitgate.core.*;

/// How git1st runs the real git for a chosen account: Windows' OpenSSH with the account's PUBLIC
/// key file and the gate's agent pipe, so the private key is never anywhere ssh could read it, and
/// the account's identity. Every other git on the machine is the official one, set up by
/// `gitgate setup` (there is no git.exe of gitgate's any more).
final class GitRun {

    static List<String> command(Settings s, Account a, boolean anonymous, List<String> args) {
        var c = new ArrayList<String>();
        c.add(s.getBinaries().getGit());
        if (a != null) {
            c.add("-c");
            c.add("core.sshCommand=" + sshCommand(s, a));
            var id = s.identity(a, anonymous);
            if (id.name() != null) { c.add("-c"); c.add("user.name=" + id.name()); }
            if (id.email() != null) { c.add("-c"); c.add("user.email=" + id.email()); }
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

    static int refuse(Account a, List<PushGuard.Hit> hits) {
        System.err.println("gitgate: push refused. These commits carry terms that account " + a.getId() + " must never publish:");
        hits.forEach(h -> System.err.println("  " + h.commit() + "  [" + h.term() + "]  " + h.line().trim()));
        System.err.println("Rewrite those commits (the text is still unpublished), or, if a hit is a false match, push with GITGATE_ALLOW_FORBIDDEN=1.");
        return 1;
    }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /// On Windows a child gets one command line, which it splits by the MSVC rules. ProcessBuilder
    /// wraps an argument with a space in quotes but does not escape the quotes inside it, so
    /// `--jq '.a + " x"'` reached gh as two arguments. Quoting by those rules ourselves fixes it:
    /// ProcessBuilder leaves an argument that is already quoted as it is.
    static String winArg(String a) {
        if (!a.isEmpty() && a.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) return a;
        var out = new StringBuilder("\"");
        var slashes = 0;
        for (var ch : a.toCharArray()) {
            if (ch == '\\') { slashes++; continue; }
            if (ch == '"') { out.append("\\".repeat(slashes * 2 + 1)).append('"'); slashes = 0; continue; }
            out.append("\\".repeat(slashes)).append(ch);
            slashes = 0;
        }
        return out.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    static int exec(List<String> command, Map<String, String> env) {
        try {
            if (WINDOWS) command = command.stream().map(GitRun::winArg).toList();
            var pb = new ProcessBuilder(command).inheritIO();
            pb.environment().putAll(env);
            return pb.start().waitFor();
        } catch (Exception e) { throw new IllegalStateException("cannot run " + command.getFirst() + ": " + e.getMessage()); }
    }

    private GitRun() {}
}
