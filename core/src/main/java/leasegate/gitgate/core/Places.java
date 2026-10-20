package leasegate.gitgate.core;

import java.nio.file.Path;

/// Where gitgate keeps things, and the names of its two pipes: one for gh tokens and status, one
/// that ssh speaks the agent protocol to.
public final class Places {

    /// `-Dgitgate.pipe` renames both pipes, so a trial never meets a real gitgate.
    public static final String
        PRODUCT = "gitgate",
        PIPE = System.getProperty("gitgate.pipe", "leasegate-gitgate"),
        AGENT_PIPE = PIPE + "-agent";

    public static Path home() {
        var override = System.getProperty("gitgate.home");
        return override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), "leasegate", "gitgate");
    }

    public static Path settings() { return home().resolve("settings.toml"); }

    public static Path runDir() { return home().resolve("run"); }

    public static Path vault() { return home().resolve("vault.json"); }

    public static Path passphrase() { return home().resolve("passphrase.json"); }

    public static Path keyedApps() { return home().resolve("keyed-apps.json"); }

    /// Public halves only: what ssh is pointed at with `-i`, so it asks the agent for that key.
    public static Path publicKey(String accountId) { return home().resolve("keys").resolve(accountId + ".pub"); }

    /// What `SSH_AUTH_SOCK` is set to for the git and ssh that gitgate starts.
    public static String agentSocket() { return "\\\\.\\pipe\\" + AGENT_PIPE; }

    /// The same pipe as Windows OpenSSH accepts it in `-o IdentityAgent=`: forward slashes only
    /// (measured with OpenSSH_for_Windows_9.5; `\\.\pipe\` there gives "No such file or directory").
    /// Named on ssh's own command line, it does not depend on an environment variable surviving
    /// git and its shell on the way down.
    public static String agentForSsh() { return "//./pipe/" + AGENT_PIPE; }

    private Places() {}
}
