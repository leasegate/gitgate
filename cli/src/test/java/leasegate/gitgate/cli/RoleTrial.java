package leasegate.gitgate.cli;

/// The exe name decides the role and, for an alias, the account.
/// java -cp <classes> leasegate.gitgate.cli.RoleTrial
public class RoleTrial {
    public static void main(String[] a) {
        var ok = Main.role("git.exe").tool().isEmpty()
            && Main.role("GH.EXE").equals(new Main.Role("gh", null))
            && Main.role("git1st.exe").equals(new Main.Role("git1st", null))
            && Main.role("gitwork.exe").tool().isEmpty()
            && Main.role("ghwork").equals(new Main.Role("gh", "work"))
            && Main.role("tool.exe").tool().isEmpty()
            // Windows command-line quoting (MSVC rules), so an argument with quotes reaches the child whole
            && GitRun.winArg("plain").equals("plain")
            && GitRun.winArg("two words").equals("\"two words\"")
            && GitRun.winArg(".a + \" x\"").equals("\".a + \\\" x\\\"\"")
            && GitRun.winArg("C:\\dir\\").equals("C:\\dir\\")
            && GitRun.winArg("C:\\my dir\\").equals("\"C:\\my dir\\\\\"")
            && GitRun.winArg("").equals("\"\"");
        System.out.println(ok ? "ALL CHECKS PASSED" : "FAILED");
        System.exit(ok ? 0 : 1);
    }
}
