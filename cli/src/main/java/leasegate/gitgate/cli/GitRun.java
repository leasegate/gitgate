package leasegate.gitgate.cli;

import java.util.List;
import java.util.Map;

/// Running the real gh as a child, with its arguments intact.
final class GitRun {

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
