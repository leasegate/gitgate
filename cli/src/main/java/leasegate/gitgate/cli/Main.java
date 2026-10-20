package leasegate.gitgate.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// One jar behind several exes, told apart by the exe's own name (jr passes its path as
/// `io.github.jarrunner.jr.exe`): `git`, `gh` and `git1st` do what they say; `git<account>` and
/// `gh<account>` are the same with the account fixed -- copies of git.exe and gh.exe under another
/// name (`gitgate aliases` makes them). Run as a plain jar, the first argument is that name.
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
                case "git" -> GitRun.proxy(args, role.account());
                case "gh" -> GhRun.proxy(args, role.account());
                case "git1st" -> new Git1st(role.account()).run();
                default -> usage();
            });
        } catch (IllegalStateException e) {
            System.err.println("gitgate: " + e.getMessage());
            System.exit(128);
        }
    }

    static Role role(String exeName) {
        var n = exeName.toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
        if (n.startsWith("git1st")) return new Role("git1st", blankToNull(n.substring(6)));
        if (n.startsWith("git")) return new Role("git", blankToNull(n.substring(3)));
        if (n.startsWith("gh")) return new Role("gh", blankToNull(n.substring(2)));
        return new Role("", null);
    }

    static String blankToNull(String s) { return s.isBlank() ? null : s; }

    static int usage() {
        System.err.println("usage: java -jar gitgate-cli.jar git|gh|git1st|git<account>|gh<account> [args...]  (the exes pass their own name)");
        return 2;
    }

    private Main() {}
}
