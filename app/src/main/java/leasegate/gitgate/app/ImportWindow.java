package leasegate.gitgate.app;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import leasegate.gitgate.core.Settings;
import leasegate.sshagent.OpenSshKeyFile;
import leasegate.ui.ApprovalParts;
import leasegate.ui.FKeys;

/// Moves what the old tools kept in the open into the vault, once. Every token found is checked
/// with GitHub first and only one that works is kept, per account; the environment variable wins
/// when both work, because that is the one the old aliases relied on. Key files are imported. The
/// old copies are listed afterwards and left for the human to delete: nothing here deletes a file.
final class ImportWindow {

    static void show(Settings settings, Secrets secrets, Runnable changed) {
        var stage = new Stage();
        var found = OldCredentials.find(settings);
        var report = new TextArea();
        report.setEditable(false);
        report.setWrapText(true);
        report.setPrefRowCount(14);
        report.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 11;");
        var lines = new ArrayList<String>();
        if (found.isEmpty()) lines.add("Nothing found: no old settings.xml, no GH<ID>_GITHUB_TOKEN variables.");
        for (var f : found) lines.add(f.account() + "  " + (f.token() != null ? "token" : "") + (f.keyFile() != null ? (f.token() != null ? " + " : "") + "key file " + f.keyFile() : "") + "  from " + f.source());
        report.setText(String.join("\n", lines) + "\n\nF2 checks every token with GitHub and imports what works.");
        Runnable go = () -> {
            var out = new ArrayList<String>();
            var tokenDone = new HashSet<String>();
            for (var f : sortedEnvFirst(found)) {
                if (f.token() != null && !tokenDone.contains(f.account())) {
                    var c = GitHubCheck.of(f.token());
                    out.add(f.account() + " token from " + f.source() + ": " + c.describe());
                    if (c.ok()) { secrets.vault.put(Secrets.token(f.account(), false), f.token()); tokenDone.add(f.account()); out.add("  -> stored as " + f.account() + "'s everyday token"); }
                }
                if (f.keyFile() != null && secrets.keys.get(f.account()).isEmpty()) {
                    try {
                        var k = OpenSshKeyFile.parse(Files.readString(f.keyFile()));
                        secrets.keys.put(f.account(), k);
                        out.add(f.account() + " key " + f.keyFile() + ": imported, " + k.fingerprint());
                    } catch (Exception e) { out.add(f.account() + " key " + f.keyFile() + ": NOT imported, " + e.getMessage()); }
                }
            }
            out.add("");
            out.add("The old copies still exist, readable by any program, until you remove them:");
            found.stream().map(OldCredential::source).distinct().forEach(s -> out.add("  " + s));
            found.stream().filter(f -> f.keyFile() != null).forEach(f -> out.add("  " + f.keyFile() + "  (and its .pub)"));
            out.add("Remove them once git and gh work through gitgate. For the strongest result, rotate: make new keys and tokens here, and delete the old ones on GitHub.");
            report.setText(String.join("\n", out));
            changed.run();
        };
        var keys = new FKeys().bind(KeyCode.F2, "Check and import", "#2e7d32", go).bind(KeyCode.ESCAPE, "Close", "#777", stage::close);
        var root = new VBox(10, new Label("Credentials the tools before gitgate kept in the open"), report, keys.bar,
            ApprovalParts.small("F2 check and import · Esc closes · nothing here deletes a file", "#777"));
        root.setPadding(new Insets(14));
        root.setPrefWidth(760);
        var scene = new Scene(root);
        keys.install(scene);
        stage.setTitle("gitgate: import the old credentials");
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.show();
    }

    static List<OldCredential> sortedEnvFirst(List<OldCredential> found) {
        var l = new ArrayList<>(found);
        l.sort((a, b) -> Boolean.compare(!a.source().startsWith("environment"), !b.source().startsWith("environment")));
        return l;
    }

    private ImportWindow() {}
}
