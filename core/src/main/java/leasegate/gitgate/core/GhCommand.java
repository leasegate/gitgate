package leasegate.gitgate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// What a gh command line asks for, decided before any token leaves the gate. A token handed to gh
/// is readable by gh, so the commands whose purpose is to print or move the token are refused
/// outright; those that cannot be undone, or that run other code with the token in reach (aliases,
/// extensions), are ADMIN; reading is READ; everything else is WRITE.
public sealed interface GhCommand {

    record Allowed(Tier tier, String detail) implements GhCommand {}

    record Refused(String reason) implements GhCommand {}

    Set<String> READ_VERBS = Set.of("list", "view", "status", "diff", "checks", "browse", "search", "watch", "download", "clone", "ls", "show", "logs", "verify");

    Set<String> ADMIN_COMMANDS = Set.of("secret", "ssh-key", "gpg-key", "extension", "ext", "extensions", "attestation", "codespace", "cs", "org");

    static GhCommand classify(List<String> args) {
        var pos = new ArrayList<String>();
        for (var a : args) if (!a.startsWith("-")) pos.add(a.toLowerCase(Locale.ROOT));
        if (pos.isEmpty()) return new Allowed(Tier.READ, "gh " + String.join(" ", args));
        var cmd = pos.getFirst();
        var sub = pos.size() > 1 ? pos.get(1) : "";
        var detail = "gh " + String.join(" ", pos.subList(0, Math.min(pos.size(), 3)));
        if (cmd.equals("auth")) {
            if (sub.equals("status") && args.stream().noneMatch(a -> a.equals("-t") || a.equals("--show-token")))
                return new Allowed(Tier.READ, detail);
            return new Refused("`gh auth " + sub + "` is refused under gitgate: the token is held by the gate and is never printed, stored by gh, or replaced from here. Change it from the gitgate tray menu.");
        }
        if (cmd.equals("alias")) return sub.equals("list") ? new Allowed(Tier.READ, detail) : new Allowed(Tier.ADMIN, detail + " (an alias can run any shell command with the token in reach)");
        if (cmd.equals("api")) return api(args, detail);
        if (ADMIN_COMMANDS.contains(cmd)) return new Allowed(READ_VERBS.contains(sub) ? Tier.READ : Tier.ADMIN, detail);
        if (cmd.equals("repo") && Set.of("delete", "archive", "unarchive", "rename", "edit", "deploy-key").contains(sub)) return new Allowed(Tier.ADMIN, detail);
        if (cmd.equals("release") && sub.equals("delete")) return new Allowed(Tier.ADMIN, detail);
        if (cmd.equals("variable") && !READ_VERBS.contains(sub)) return new Allowed(Tier.ADMIN, detail);
        if (cmd.equals("ruleset")) return new Allowed(READ_VERBS.contains(sub) ? Tier.READ : Tier.ADMIN, detail);
        if (Set.of("help", "version", "completion", "config", "status").contains(cmd)) return new Allowed(Tier.READ, detail);
        return new Allowed(READ_VERBS.contains(sub) ? Tier.READ : Tier.WRITE, detail);
    }

    /// `gh api`: GET without fields reads; DELETE, PUT and PATCH are ADMIN (a PUT sets collaborators
    /// and branch protection); a POST, any field, or graphql is WRITE.
    static GhCommand api(List<String> args, String detail) {
        var method = "GET";
        var fields = false;
        for (var i = 0; i < args.size(); i++) {
            var a = args.get(i);
            if ((a.equals("-X") || a.equals("--method")) && i + 1 < args.size()) method = args.get(i + 1).toUpperCase(Locale.ROOT);
            else if (a.startsWith("--method=")) method = a.substring(9).toUpperCase(Locale.ROOT);
            else if (a.startsWith("-X") && a.length() > 2) method = a.substring(2).toUpperCase(Locale.ROOT);
            else if (Set.of("-f", "-F", "--field", "--raw-field", "--input").contains(a) || a.startsWith("--field=") || a.startsWith("--raw-field=")) fields = true;
        }
        var target = args.stream().filter(a -> !a.startsWith("-") && !a.equalsIgnoreCase("api")).findFirst().orElse("");
        var d = "gh api " + method + " " + target;
        if (Set.of("DELETE", "PUT", "PATCH").contains(method)) return new Allowed(Tier.ADMIN, d);
        if (target.equalsIgnoreCase("graphql") || method.equals("POST") || fields) return new Allowed(Tier.WRITE, d);
        return new Allowed(Tier.READ, d);
    }
}
