package leasegate.gitgate.cli;

import java.util.List;
import java.util.Map;

/// A command run with its output kept, for the steps whose answer decides what happens next.
record Captured(int exit, String out) {

    static Captured run(List<String> command, Map<String, String> env) {
        try {
            var pb = new ProcessBuilder(GitRun.WINDOWS ? command.stream().map(GitRun::winArg).toList() : command).redirectErrorStream(true);
            pb.environment().putAll(env);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes());
            return new Captured(p.waitFor(), out.trim());
        } catch (Exception e) { throw new IllegalStateException("cannot run " + command.getFirst() + ": " + e.getMessage()); }
    }
}
