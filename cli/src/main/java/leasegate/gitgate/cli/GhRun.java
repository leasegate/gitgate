package leasegate.gitgate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import leasegate.gitgate.core.*;
import leasegate.transport.LeaseDeniedException;

/// `gh` with the right account's token, fetched from the gate for this one run and handed only to
/// the gh process started here, in its environment. What the command could do is classified first:
/// commands that would print or replace the token are refused before the gate is even asked.
final class GhRun {

    static int proxy(List<String> args, String pinned) {
        var s = SettingsStore.load();
        var needs = !args.isEmpty() && !args.getFirst().equals("--version") && !args.getFirst().equals("help");
        var run = Choose.decide(s, java.nio.file.Path.of("").toAbsolutePath(), pinned, null, needs, "gh");
        Notice.account(s, run.line(), args.isEmpty());
        if (run.account() == null) return GitRun.exec(command(s, args), Map.of());
        var a = run.account();
        return switch (GhCommand.classify(args)) {
            case GhCommand.Refused(var reason) -> { System.err.println("gitgate: " + reason); yield 1; }
            case GhCommand.Allowed(var tier, var detail) -> {
                String token;
                try { token = Gate.token(a.getId(), tier, detail, "gh"); }
                catch (LeaseDeniedException e) { System.err.println("gitgate: " + e.getMessage()); yield 1; }
                yield GitRun.exec(command(s, args), Map.of("GITHUB_TOKEN", token, "GH_TOKEN", token, "GH_PROMPT_DISABLED", "1"));
            }
        };
    }

    static List<String> command(Settings s, List<String> args) {
        var c = new ArrayList<String>();
        c.add(s.getBinaries().getGh());
        c.addAll(args);
        return c;
    }

    private GhRun() {}
}
