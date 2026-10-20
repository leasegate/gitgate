package leasegate.gitgate.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import leasegate.gitgate.core.Places;
import leasegate.gitgate.core.SettingsStore;
import leasegate.transport.LeaseDeniedException;
import leasegate.transport.Links;

/// `gitgate` with no arguments starts the tray. With a verb it is a small command line that never
/// sees a secret: `migrate` writes settings.toml from the old tools' files, `status` asks the
/// running tray what it holds (names and fingerprints only), `pubkey <account>` prints the public
/// key to give to GitHub.
public final class GitgateMain {

    public static void main(String[] args) {
        if (args.length == 0) { GitgateApp.main(args); return; }
        System.exit(switch (args[0]) {
            case "migrate" -> Migrate.run();
            case "status" -> status();
            case "pubkey" -> pubkey(args.length > 1 ? args[1] : null);
            case "aliases" -> aliases(args.length > 1 ? args[1] : null);
            default -> {
                System.err.println("usage: gitgate                  start the tray (it holds the keys and tokens)");
                System.err.println("       gitgate migrate          write " + Places.settings() + " from the old settings files");
                System.err.println("       gitgate status           what the running tray holds: accounts, key fingerprints, tokens present");
                System.err.println("       gitgate pubkey <account> the account's public key line, for GitHub");
                System.err.println("       gitgate aliases <dir>    copy <dir>\\git.exe and gh.exe to git<account>.exe and gh<account>.exe, for every account");
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

    /// An alias is the same exe under another name: it reads its account from that name.
    static int aliases(String dir) {
        if (dir == null) { System.err.println("usage: gitgate aliases <folder holding git.exe and gh.exe>"); return 2; }
        try {
            var d = Path.of(dir);
            for (var a : SettingsStore.load().getAccount()) {
                for (var tool : List.of("git", "gh")) {
                    var from = d.resolve(tool + ".exe");
                    var to = d.resolve(tool + a.getId() + ".exe");
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println(to + "  (" + tool + " as " + a.getId() + ")");
                }
            }
            return 0;
        } catch (Exception e) { System.err.println("gitgate: " + e.getMessage()); return 1; }
    }

    private GitgateMain() {}
}
