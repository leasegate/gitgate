package leasegate.gitgate.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import leasegate.gitgate.core.Places;
import leasegate.gitgate.core.*;
import leasegate.transport.LeaseDeniedException;
import leasegate.transport.Links;

/// `gitgate` with no arguments starts the tray. With a verb it is a small command line that never
/// sees a secret: `status` asks the
/// running tray what it holds (names and fingerprints only), `pubkey <account>` prints the public
/// key to give to GitHub.
public final class GitgateMain {

    public static void main(String[] args) {
        if (args.length == 0) { GitgateApp.main(args); return; }
        System.exit(switch (args[0]) {
            case "status" -> status();
            case "pubkey" -> pubkey(args.length > 1 ? args[1] : null);
            case "which" -> which(args.length > 1 ? args[1] : ".");
            case "assign" -> assign(List.of(args).subList(1, args.length));
            case "audit" -> audit(List.of(args).subList(1, args.length));
            case "setup" -> setup(args.length > 1 && args[1].equals("--undo"));
            case "hook" -> hook(List.of(args).subList(1, args.length));
            default -> {
                System.err.println("usage: gitgate                  start the tray (it holds the keys and tokens)");
                System.err.println("       gitgate status           what the running tray holds: accounts, key fingerprints, tokens present");
                System.err.println("       gitgate pubkey <account> the account's public key line, for GitHub");
                System.err.println("       gitgate which [dir]      which account a repository is, and what each rule says");
                System.err.println("       gitgate assign <account> [--anonymous] [dir]   record a repository's account in its own .git/config");
                System.err.println("       gitgate audit [dir...]   commits already made under another account's identity (default: every account's folders)");
                System.err.println("       gitgate setup [--undo]   make the official git and ssh work through gitgate: its git config (included from ~/.gitconfig), its hooks, github.com in ~/.ssh/config");
                System.err.println("       gitgate hook pre-commit|pre-push ...   what gitgate's git hooks run");
                yield 2;
            }
        });
    }

    static int status() {
        try (var io = Links.open(Places.PRODUCT, Places.runDir(), Places.PIPE)) {
            io.writeFrame("{\"gate\":{\"v\":2,\"op\":\"status\"}}");
            var g = new ObjectMapper().readTree(io.readFrame()).path("gate");
            for (var a : g.path("accounts"))
                System.out.printf("%-4s %-10s ssh: %-8s key: %-52s token: %s%s%n", a.path("id").asText(), a.path("name").asText(), a.path("ssh").asText(),
                    a.path("key").asText("(none)"), a.path("token").asBoolean() ? "yes" : "no", a.path("adminToken").asBoolean() ? " (+admin)" : "");
            System.out.println(g.path("approvals").asInt() + " approval(s) in force");
            return 0;
        } catch (LeaseDeniedException e) { System.err.println("gitgate is not running"); return 1; }
        catch (Exception e) { System.err.println("gitgate: " + e.getMessage()); return 1; }
    }

    static int pubkey(String account) {
        if (account == null) { System.err.println("usage: gitgate pubkey <account>"); return 2; }
        try { System.out.print(Files.readString(Places.publicKey(account))); return 0; }
        catch (Exception e) { System.err.println("gitgate: no public key for " + account + " (" + Places.publicKey(account) + ")"); return 1; }
    }

    static int which(String dir) {
        var s = SettingsStore.load();
        var path = Path.of(dir).toAbsolutePath().normalize();
        var repo = RepoConfig.find(path);
        System.out.println("folder     : " + path);
        System.out.println("repository : " + repo.map(r -> r.workTree().toString()).orElse("(none)"));
        repo.ifPresent(r -> {
            System.out.println("recorded   : " + (r.account() == null ? "(nothing)" : r.account() + (r.anonymous() ? ", anonymous commits" : "")));
            System.out.println("identity   : " + r.userName() + " <" + r.userEmail() + ">");
            r.remotes().forEach((n, u) -> System.out.println("remote     : " + n + "  " + u + RepoConfig.orgOf(u).flatMap(s::byOrg).map(a -> "   (org of " + a.getId() + ")").orElse("")));
        });
        System.out.println("folder rule: " + leasegate.gitgate.core.AccountMatcher.match(s, path).map(m -> m.account().getId() + "  (" + m.rule() + ")").orElse("(none)"));
        System.out.println("answer     : " + switch (RepoDecision.resolve(s, path, null, null)) {
            case RepoDecision.Decided d -> d.account().getId() + (d.anonymous() ? ", anonymous" : "") + "  -- " + d.how() + (d.record() ? " (will be written into the repository on the next git command)" : "");
            case RepoDecision.Ask a -> "UNDECIDED -- " + a.why() + ". The next commit or push will ask in the gitgate window.";
            case RepoDecision.Refuse r -> "REFUSED -- " + r.message();
            case RepoDecision.Plain p -> "plain git -- " + p.why();
        });
        return 0;
    }

    /// Deliberate, by a human at a terminal: what the window would have asked.
    static int assign(List<String> args) {
        var anonymous = args.contains("--anonymous");
        var rest = args.stream().filter(a -> !a.equals("--anonymous")).toList();
        if (rest.isEmpty()) { System.err.println("usage: gitgate assign <account> [--anonymous] [dir]"); return 2; }
        var s = SettingsStore.load();
        var account = s.byId(rest.getFirst());
        if (account.isEmpty()) { System.err.println("gitgate: no account " + rest.getFirst() + "; the accounts are " + s.getAccount().stream().map(Account::getId).toList()); return 2; }
        var repo = RepoConfig.find(Path.of(rest.size() > 1 ? rest.get(1) : ".").toAbsolutePath());
        if (repo.isEmpty()) { System.err.println("gitgate: not inside a git repository"); return 1; }
        RepoRecord.write(s.getBinaries().getGit(), repo.get().workTree(), s, account.get(), anonymous, "gitgate assign");
        System.out.println(repo.get().workTree() + " is " + account.get().getId() + " (" + s.identity(account.get(), anonymous) + ")");
        return 0;
    }

    static int audit(List<String> dirs) {
        var s = SettingsStore.load();
        if (dirs.isEmpty()) { System.out.print(RulesWindow.audit(s)); return 0; }
        int leaky = 0;
        for (var d : dirs) {
            try {
                for (var r : Audit.repos(Path.of(d), 4)) {
                    var f = Audit.check(s, r);
                    if (f == null || f.pushed() + f.local() == 0) continue;
                    leaky++;
                    System.out.println(r + "  [" + f.account() + "]  pushed: " + f.pushed() + "  only here: " + f.local());
                    f.examples().forEach(x -> System.out.println("      " + x));
                }
            } catch (Exception e) { System.err.println("gitgate: cannot walk " + d + ": " + e.getMessage()); }
        }
        System.out.println(leaky + " repositories carry commits under another account's identity");
        return leaky == 0 ? 0 : 3;
    }

    static int setup(boolean undo) {
        try {
            var s = SettingsStore.load();
            var done = undo ? GitSetup.undo(s) : GitSetup.install(s, selfExe());
            done.forEach(d -> System.out.println((undo ? "undone: " : "set: ") + d));
            if (done.isEmpty()) System.out.println(undo ? "nothing of gitgate's was set" : "already set");
            return 0;
        } catch (Exception e) { System.err.println("gitgate: " + e.getMessage()); return 1; }
    }

    /// The exe the hooks call back: this one (jr names it), else `gitgate` on PATH.
    static String selfExe() {
        var exe = System.getProperty("io.github.jarrunner.jr.exe");
        return exe != null && !exe.isBlank() ? exe : "gitgate";
    }

    static int hook(List<String> args) {
        if (args.isEmpty()) { System.err.println("usage: gitgate hook pre-commit | pre-push <remote> <url>"); return 2; }
        var s = SettingsStore.load();
        var here = Path.of("").toAbsolutePath();
        Hooks.Asker ask = a -> askTray(a, here);
        return switch (args.getFirst()) {
            case "pre-commit" -> Hooks.preCommit(s, here, ask, System.err);
            case "pre-push" -> Hooks.prePush(s, here, args.size() > 2 ? args.get(2) : "", ask, System.err);
            default -> 0;
        };
    }

    /// The tray's "which account" window, over its pipe; empty when it is not running or nobody answered.
    static java.util.Optional<Hooks.Choice> askTray(RepoDecision.Ask a, Path here) {
        var s = SettingsStore.load();
        var repo = RepoConfig.find(here);
        var m = new ObjectMapper();
        try (var io = Links.open(Places.PRODUCT, Places.runDir(), Places.PIPE)) {
            var body = m.createObjectNode().put("v", 2).put("op", "decide").put("app", "git hook")
                .put("repo", repo.map(r -> r.workTree().toString()).orElse(here.toString()))
                .put("remotes", repo.map(r -> String.join("  ", r.remotes().values())).orElse("")).put("why", a.why()).put("suggested", a.suggested());
            a.sources().forEach(body.putArray("sources")::add);
            repo.ifPresent(r -> RepoRecord.recentAuthors(s.getBinaries().getGit(), r.workTree(), 4).forEach(body.putArray("authors")::add));
            io.writeFrame(m.createObjectNode().set("gate", body).toString());
            var reply = m.readTree(io.readFrame()).path("gate");
            if (!reply.path("ok").asBoolean(false)) return java.util.Optional.empty();
            return java.util.Optional.of(new Hooks.Choice(reply.path("account").asText(), reply.path("anonymous").asBoolean(false)));
        } catch (LeaseDeniedException e) {
            System.err.println("gitgate: the gitgate tray is not running, so it cannot ask.");
            return java.util.Optional.empty();
        } catch (Exception e) { System.err.println("gitgate: " + e.getMessage()); return java.util.Optional.empty(); }
    }

    private GitgateMain() {}
}
