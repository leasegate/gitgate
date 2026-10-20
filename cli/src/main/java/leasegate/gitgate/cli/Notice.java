package leasegate.gitgate.cli;

import leasegate.gitgate.core.Settings;

/// The one-line account notice, written where the settings say. stderr by default: on stdout it
/// would mix into git's own output (`git rev-parse HEAD`, `git rev-list`), which scripts read.
final class Notice {

    static void account(Settings s, String line, boolean noArgs) {
        var where = noArgs ? s.getOutput().getNoArgs() : s.getOutput().getAccountLine();
        switch (where == null ? "stderr" : where.toLowerCase()) {
            case "stdout", "std.out", "info" -> { System.out.println(line); System.out.flush(); }
            case "none" -> {}
            default -> { System.err.println(line); System.err.flush(); }
        }
    }

    private Notice() {}
}
