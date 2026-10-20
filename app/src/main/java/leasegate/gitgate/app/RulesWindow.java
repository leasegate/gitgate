package leasegate.gitgate.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import leasegate.gitgate.core.*;
import leasegate.frame.*;
import leasegate.ui.ApprovalParts;

/// "Repositories and rules": which folders and GitHub organisations belong to which account (from
/// settings.toml), and every repository gitgate has recorded, with its account and whether its
/// commits are anonymous. A repository's account is changed here with the keyboard and written
/// straight into that repository. F7 audits every repository under the accounts' folders for
/// commits already made under another account's identity.
final class RulesWindow {

    static void show(Brand brand) {
        var frame = GateFrame.resizable(brand, "Repositories and rules").notOnTop().inTaskbar();
        var stage = frame.stage;
        var rules = new TextArea();
        rules.setEditable(false);
        rules.setPrefRowCount(6);
        rules.setStyle("-fx-font-family: Consolas, monospace;");
        var repos = new ListView<String>();
        repos.setPrefHeight(260);
        var paths = new ArrayList<String>();
        var message = new Label(" ");
        message.setWrapText(true);
        var report = new TextArea();
        report.setEditable(false);
        report.setPrefRowCount(8);
        report.setStyle("-fx-font-family: Consolas, monospace;");

        Runnable refresh = () -> {
            var s = SettingsStore.load();
            var text = new StringBuilder();
            for (var a : s.getAccount())
                text.append(a.getId()).append(" (").append(a.getName()).append(")  commits as ").append(a.getUserName()).append(" <").append(a.getUserEmail()).append(">\n")
                    .append("    folders: ").append(String.join(", ", a.getPaths())).append(a.getPathRegex().isEmpty() ? "" : "  + " + String.join(", ", a.getPathRegex())).append('\n')
                    .append("    GitHub orgs: ").append(String.join(", ", a.getOrgs())).append('\n');
            text.append("anonymous commits: ").append(s.getAnonymous().getUserName()).append(" <").append(s.getAnonymous().getUserEmail()).append(">");
            rules.setText(text.toString());
            var keep = repos.getSelectionModel().getSelectedIndex();
            paths.clear();
            var rows = new ArrayList<String>();
            RepoRecord.seen().forEach((path, seen) -> {
                var now = RepoConfig.find(Path.of(path));
                var gone = now.isEmpty() || !Files.isDirectory(Path.of(path));
                var account = now.map(RepoConfig::account).orElse(seen.account());
                var anon = now.map(RepoConfig::anonymous).orElse(seen.anonymous());
                paths.add(path);
                rows.add(String.format("%-4s %-10s %s%s", account, anon ? "anonymous" : "", path, gone ? "   (folder gone)" : ""));
            });
            repos.setItems(FXCollections.observableArrayList(rows));
            if (keep >= 0 && keep < rows.size()) repos.getSelectionModel().select(keep); else repos.getSelectionModel().selectFirst();
        };

        Runnable nextAccount = () -> change(repos, paths, message, refresh, true);
        Runnable anonymous = () -> change(repos, paths, message, refresh, false);
        Runnable openFolder = () -> {
            var i = repos.getSelectionModel().getSelectedIndex();
            if (i >= 0) GitgateApp.open(paths.get(i));
        };
        Runnable audit = () -> {
            report.setText("auditing every repository under the accounts' folders...\n");
            Thread.ofPlatform().daemon().name("gitgate-audit").start(() -> {
                var out = audit(SettingsStore.load());
                Platform.runLater(() -> report.setText(out));
            });
        };
        var fkeys = new FKeys().bind(KeyCode.F3, "Next account", "#2e6da8", nextAccount)
            .bind(KeyCode.F4, "Anonymous on/off", "#5b3fa0", anonymous)
            .bind(KeyCode.F5, "Refresh", "#555", refresh)
            .bind(KeyCode.F6, "Open folder", "#555", openFolder)
            .bind(KeyCode.F7, "Audit all", "#8a4b00", audit)
            .bind(KeyCode.F8, "settings.toml", "#555", () -> GitgateApp.open(Places.settings().toString()))
            .bind(KeyCode.ESCAPE, "Close", "#777", stage::close);
        var root = new VBox(8, new Label("Rules (settings.toml, F8 to edit)"), rules,
            new Label("Recorded repositories (each carries its account in its own .git/config)"), repos, message, fkeys.bar, report,
            ApprovalParts.small("Arrows choose · F3 moves the repository to the next account · F4 anonymous · F7 finds commits made under the wrong account · Esc closes", "#777"));
        root.setPadding(new Insets(14));
        root.setPrefWidth(900);
        var scene = frame.scene(root);
        fkeys.install(scene);
        refresh.run();
        stage.show();
        repos.requestFocus();
    }

    /// F3: the next account in settings order. F4: anonymous on/off. Written into the repository.
    static void change(ListView<String> repos, List<String> paths, Label message, Runnable refresh, boolean nextAccount) {
        var i = repos.getSelectionModel().getSelectedIndex();
        if (i < 0) return;
        var s = SettingsStore.load();
        var dir = Path.of(paths.get(i));
        var repo = RepoConfig.find(dir);
        if (repo.isEmpty()) { say(message, "not a repository any more: " + dir, ApprovalParts.RED); return; }
        var ids = s.getAccount().stream().map(Account::getId).toList();
        var current = repo.get().account();
        var at = current == null ? -1 : ids.stream().map(String::toLowerCase).toList().indexOf(current.toLowerCase());
        var account = s.byId(nextAccount ? ids.get((at + 1) % ids.size()) : (current != null ? current : ids.getFirst())).orElseThrow();
        var anon = nextAccount ? repo.get().anonymous() : !repo.get().anonymous();
        try {
            RepoRecord.write(s.getBinaries().getGit(), repo.get().workTree(), s, account, anon, "changed in the gitgate window");
            say(message, repo.get().workTree() + " is now " + account.getId() + (anon ? ", anonymous commits" : "") + ". Commits made before keep their identity; F7 shows any that do not match.", ApprovalParts.GREEN);
        } catch (Exception e) { say(message, String.valueOf(e.getMessage()), ApprovalParts.RED); }
        refresh.run();
    }

    static String audit(Settings s) {
        var bases = new LinkedHashSet<Path>();
        for (var a : s.getAccount()) for (var p : a.getPaths()) if (Files.isDirectory(Path.of(p))) bases.add(Path.of(p));
        RepoRecord.seen().keySet().forEach(p -> { if (Files.isDirectory(Path.of(p))) bases.add(Path.of(p)); });
        var out = new StringBuilder();
        int repos = 0, leaky = 0;
        var done = new LinkedHashSet<Path>();
        for (var base : bases) {
            List<Path> found;
            try { found = Audit.repos(base, 4); } catch (Exception e) { out.append("cannot walk ").append(base).append(": ").append(e.getMessage()).append('\n'); continue; }
            for (var r : found) {
                if (!done.add(r)) continue;
                repos++;
                var f = Audit.check(s, r);
                if (f == null || f.pushed() + f.local() == 0) continue;
                leaky++;
                out.append(r).append("  [").append(f.account()).append("]  pushed: ").append(f.pushed()).append("  only here: ").append(f.local()).append('\n');
                f.examples().forEach(x -> out.append("      ").append(x).append('\n'));
            }
        }
        out.insert(0, repos + " repositories checked, " + leaky + " carry commits under another account's identity\n"
            + (leaky == 0 ? "" : "('only here' can still be rewritten before the next push; 'pushed' is already on GitHub)\n"));
        return out.toString();
    }

    static void say(Label l, String text, String color) {
        l.setText(text);
        l.setStyle("-fx-text-fill: " + color + ";");
    }

    private RulesWindow() {}
}
