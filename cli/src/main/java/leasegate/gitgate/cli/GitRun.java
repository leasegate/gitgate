package leasegate.gitgate.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import leasegate.gitgate.core.*;

/// `git` with the right identity for the directory it runs in. Under an account whose key is in the
/// gate, the real git is pointed at Windows' OpenSSH with the account's PUBLIC key file and the
/// gate's agent pipe, so the private key is never anywhere ssh could read it; every signature is
/// approved by the gate. A push is first searched for the account's forbidden terms.
final class GitRun {

    static final Set<String> REMOTE = Set.of("push", "pull", "fetch", "clone", "ls-remote", "remote", "submodule");
    static final String ALLOW_FORBIDDEN = "--gitgate-allow-forbidden", ALLOW_AUTHOR = "--gitgate-allow-author";

    static int proxy(List<String> args, String pinned) {
        var s = SettingsStore.load();
        var allow = args.contains(ALLOW_FORBIDDEN);
        var allowAuthor = args.contains(ALLOW_AUTHOR);
        var rest = args.stream().filter(x -> !x.equals(ALLOW_FORBIDDEN) && !x.equals(ALLOW_AUTHOR)).toList();
        var verb = verb(rest);
        var cwd = Path.of("").toAbsolutePath();
        var clone = verb.equals("clone") ? cloneArgs(rest) : null;
        var run = Choose.decide(s, repoDir(rest), pinned, clone == null ? null : clone.url(), RepoDecision.NEEDS_ACCOUNT.contains(verb), "git");
        Notice.account(s, run.line(), args.isEmpty());
        var a = run.account();
        if (a == null) return exec(command(s, null, false, rest), Map.of());
        if (a.viaGate() && REMOTE.contains(verb)) {
            if (!Files.exists(Places.publicKey(a.getId())))
                throw new IllegalStateException("account " + a.getId() + " has no SSH key in gitgate yet: open the gitgate tray menu, Import or Make an SSH key");
            if (!Gate.running()) throw new IllegalStateException("gitgate is not running, and it holds account " + a.getId() + "'s SSH key: start gitgate.exe (it lives in the tray)");
        }
        if (verb.equals("push")) {
            var allowed = run.anonymous() ? Set.of(s.getAnonymous().getUserEmail()) : a.ownEmails();
            var strangers = IdentityGuard.scan(s.getBinaries().getGit(), repoDir(rest), allowed);
            if (!strangers.isEmpty() && !allowAuthor) return refuseAuthors(s, a, run.anonymous(), strangers);
            if (!strangers.isEmpty()) System.err.println("gitgate: pushing despite " + strangers.size() + " commit(s) under another identity, as " + ALLOW_AUTHOR + " asked");
            var hits = PushGuard.scan(s.getBinaries().getGit(), repoDir(rest), a);
            if (!hits.isEmpty() && !allow) return refuse(a, hits);
            if (!hits.isEmpty()) System.err.println("gitgate: pushing despite " + hits.size() + " forbidden term(s), as " + ALLOW_FORBIDDEN + " asked");
        }
        var exit = exec(command(s, a, run.anonymous(), rest), env(a));
        if (exit == 0 && clone != null) Choose.recordClone(s, cwd, clone.url(), clone.target(), run);
        return exit;
    }

    record CloneArgs(String url, String target) {}

    /// The URL and the optional target folder of `git clone [options] <url> [<dir>]`.
    static CloneArgs cloneArgs(List<String> args) {
        var plain = new ArrayList<String>();
        var withValue = Set.of("-o", "--origin", "-b", "--branch", "-u", "--upload-pack", "--reference", "--separate-git-dir", "--depth", "-c", "--config", "--template", "-j", "--jobs", "--filter");
        var seenVerb = false;
        for (var i = 0; i < args.size(); i++) {
            var x = args.get(i);
            if (!seenVerb) { if (x.equals("-c") || x.equals("-C")) i++; else if (x.equals("clone")) seenVerb = true; continue; }
            if (withValue.contains(x)) { i++; continue; }
            if (!x.startsWith("-")) plain.add(x);
        }
        return plain.isEmpty() ? null : new CloneArgs(plain.get(0), plain.size() > 1 ? plain.get(1) : null);
    }

    static List<String> command(Settings s, Account a, boolean anonymous, List<String> args) {
        var c = new ArrayList<String>();
        c.add(s.getBinaries().getGit());
        if (a != null) {
            c.add("-c");
            c.add("core.sshCommand=" + sshCommand(s, a));
            var id = s.identity(a, anonymous);
            if (id.name() != null) { c.add("-c"); c.add("user.name=" + id.name()); }
            if (id.email() != null) { c.add("-c"); c.add("user.email=" + id.email()); }
        }
        c.addAll(args);
        return c;
    }

    static int refuseAuthors(Settings s, Account a, boolean anonymous, List<IdentityGuard.Stranger> strangers) {
        System.err.println("gitgate: push refused. This repository pushes as " + a.getId() + " (" + s.identity(a, anonymous)
            + "), and these unpushed commits carry another identity:");
        strangers.forEach(x -> System.err.println("  " + x.commit() + "  author " + x.author() + "  committer " + x.committer()));
        System.err.println("They are not pushed yet, so they can still be rewritten with the right identity:");
        System.err.println("  git rebase -r <the branch's upstream, or --root> --exec \"git commit --amend --no-edit --reset-author\"");
        System.err.println("If they are rightly someone else's commits, push with " + ALLOW_AUTHOR + ".");
        return 1;
    }

    /// Quotes escaped so they survive the shell git runs the command through.
    static String sshCommand(Settings s, Account a) {
        if (!a.viaGate()) return "ssh -i \\\"" + a.getSsh().replace('\\', '/') + "\\\"";
        return s.getBinaries().getSsh().replace('\\', '/') + " -o IdentityAgent=" + Places.agentForSsh() + " -o IdentitiesOnly=yes -i \\\""
            + Places.publicKey(a.getId()).toString().replace('\\', '/') + "\\\"";
    }

    static Map<String, String> env(Account a) { return a.viaGate() ? Map.of("SSH_AUTH_SOCK", Places.agentSocket()) : Map.of(); }

    /// The git subcommand: the first argument that is not an option (and not the value of -c or -C).
    static String verb(List<String> args) {
        for (var i = 0; i < args.size(); i++) {
            var x = args.get(i);
            if (x.equals("-c") || x.equals("-C")) { i++; continue; }
            if (!x.startsWith("-")) return x;
        }
        return "";
    }

    static Path repoDir(List<String> args) {
        for (var i = 0; i + 1 < args.size(); i++) if (args.get(i).equals("-C")) return Path.of(args.get(i + 1));
        return Path.of("").toAbsolutePath();
    }

    static int refuse(Account a, List<PushGuard.Hit> hits) {
        System.err.println("gitgate: push refused. These commits carry terms that account " + a.getId() + " must never publish:");
        hits.forEach(h -> System.err.println("  " + h.commit() + "  [" + h.term() + "]  " + h.line().trim()));
        System.err.println("Rewrite those commits (the text is still unpublished), or, if a hit is a false match, push with " + ALLOW_FORBIDDEN + ".");
        return 1;
    }

    static int exec(List<String> command, Map<String, String> env) {
        try {
            var pb = new ProcessBuilder(command).inheritIO();
            pb.environment().putAll(env);
            return pb.start().waitFor();
        } catch (Exception e) { throw new IllegalStateException("cannot run " + command.getFirst() + ": " + e.getMessage()); }
    }

    private GitRun() {}
}
