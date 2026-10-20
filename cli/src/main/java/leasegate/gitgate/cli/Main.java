package leasegate.gitgate.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// One jar behind several exes, told apart by the exe's own name (jr passes its path as
/// `io.github.jarrunner.jr.exe`): `gh` does what it says; `gh<account>` is gh with the account fixed -- a copy of
/// gh.exe under another name. There is no `git`: the official git runs through gitgate (`gitgate setup`). Run as a plain jar, the first argument is that name.
public final class Main {

    record Role(String tool, String account) {}

    public static void main(String[] argv) {
        var args = List.of(argv);
        var exe = System.getProperty("io.github.jarrunner.jr.exe");
        String name;
        if (exe != null && !exe.isBlank()) name = Path.of(exe).getFileName().toString();
        else if (!args.isEmpty()) { name = args.getFirst(); args = args.subList(1, args.size()); }
        else { System.exit(usage()); return; }
        var role = role(name);
        try {
            System.exit(switch (role.tool()) {
                case "gh" -> GhRun.proxy(args, role.account());
                default -> usage();
            });
        } catch (IllegalStateException e) {
            System.err.println("gitgate: " + e.getMessage());
            System.exit(128);
        }
    }

    static Role role(String exeName) {
        var n = exeName.toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
        if (n.startsWith("gh")) return new Role("gh", blankToNull(n.substring(2)));
        return new Role("", null);
    }

    static String blankToNull(String s) { return s.isBlank() ? null : s; }

    static int usage() {
        System.err.println("usage: java -jar gitgate-cli.jar gh|gh<account> [args...]  (the exes pass their own name)");
        return 2;
    }

    private Main() {}
}
