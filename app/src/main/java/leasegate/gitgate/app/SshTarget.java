package leasegate.gitgate.app;

import java.util.regex.Pattern;

import leasegate.gitgate.core.Tier;

/// What an ssh process is about to do, read from its own command line (taken from the operating
/// system, not from anything it says). git runs `ssh ... git@github.com "git-receive-pack 'org/repo'"`
/// to push and `git-upload-pack` to fetch or clone; anything else is treated as a read with no repo.
/// `repo` is `org/name`, null when the command names none.
record SshTarget(Tier tier, String detail, String repo) {

    static final Pattern GIT = Pattern.compile("(git-receive-pack|git-upload-pack|git-upload-archive)\\s+['\"]*/?([^'\"\\s]+?)(\\.git)?['\"]*(\\s|$)");
    static final Pattern HOST = Pattern.compile("(\\S+@[\\w.-]+)");

    static SshTarget of(String commandLine) {
        var m = GIT.matcher(commandLine);
        if (m.find()) {
            var write = m.group(1).equals("git-receive-pack");
            return new SshTarget(write ? Tier.WRITE : Tier.READ, "git " + (write ? "push to " : "fetch from ") + m.group(2), m.group(2));
        }
        var h = HOST.matcher(commandLine);
        return new SshTarget(Tier.READ, "ssh " + (h.find() ? h.group(1) : "(no git command)"), null);
    }

    /// The GitHub organisation (or user) the repository belongs to.
    String org() {
        if (repo == null) return null;
        var slash = repo.indexOf('/');
        return slash > 0 ? repo.substring(0, slash) : null;
    }
}
