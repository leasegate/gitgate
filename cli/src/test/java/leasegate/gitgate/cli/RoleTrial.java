package leasegate.gitgate.cli;

/// The exe name decides the role and, for an alias, the account.
/// java -cp <classes> leasegate.gitgate.cli.RoleTrial
public class RoleTrial {
    public static void main(String[] a) {
        var ok = Main.role("git.exe").equals(new Main.Role("git", null))
            && Main.role("GH.EXE").equals(new Main.Role("gh", null))
            && Main.role("git1st.exe").equals(new Main.Role("git1st", null))
            && Main.role("gitwork.exe").equals(new Main.Role("git", "work"))
            && Main.role("ghwork").equals(new Main.Role("gh", "work"))
            && Main.role("tool.exe").tool().isEmpty();
        System.out.println(ok ? "ALL CHECKS PASSED" : "FAILED");
        System.exit(ok ? 0 : 1);
    }
}
