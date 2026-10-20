package leasegate.gitgate.app;

/// What the agent reads from an ssh command line: the host (gitgate serves GitHub only), the
/// repository and its organisation, and whether it is a push.
/// java -cp <classes> leasegate.gitgate.app.SshTargetTrial
public class SshTargetTrial {

    static int failed;

    public static void main(String[] a) {
        var push = "C:/Windows/System32/OpenSSH/ssh.exe -o SendEnv=GIT_PROTOCOL git@github.com \"git-receive-pack 'work-org/tool.git'\"";
        var fetch = "ssh.exe -o SendEnv=GIT_PROTOCOL -p 22 git@github.com \"git-upload-pack '/home-org/x'\"";
        check("host of a git push", "github.com".equals(SshTarget.host(push)));
        check("host with a port option", "github.com".equals(SshTarget.host(fetch)));
        check("host of a server login", "server.example".equals(SshTarget.host("ssh.exe admin@server.example")));
        check("no user@host: no host", SshTarget.host("ssh.exe -V") == null);
        var t = SshTarget.of(push);
        check("push: repo, org, write", "work-org/tool".equals(t.repo()) && "work-org".equals(t.org()) && t.tier() == leasegate.gitgate.core.Tier.WRITE);
        check("fetch with a leading slash: org", "home-org".equals(SshTarget.of(fetch).org()));
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }
}
