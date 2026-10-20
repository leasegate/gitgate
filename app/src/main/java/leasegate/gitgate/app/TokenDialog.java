package leasegate.gitgate.app;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import leasegate.gitgate.core.Settings;
import leasegate.ui.ApprovalParts;
import leasegate.ui.FKeys;

/// Sets or replaces an account's GitHub token: pasted here, checked with GitHub (who it belongs
/// to, whether it can delete repositories), then stored in the vault. Never on a command line,
/// never in an environment variable or a file. The everyday token should not be able to delete
/// repositories; an optional admin token, used only for ADMIN requests, may.
final class TokenDialog {

    static void show(Settings settings, Secrets secrets, Runnable changed) {
        var stage = new Stage();
        var accounts = new ChoiceBox<String>(FXCollections.observableArrayList(settings.getAccount().stream().map(a -> a.getId() + " · " + a.getName()).toList()));
        accounts.getSelectionModel().selectFirst();
        var kind = new ChoiceBox<String>(FXCollections.observableArrayList("everyday token", "admin token (only for ADMIN requests)"));
        kind.getSelectionModel().selectFirst();
        var token = new PasswordField();
        token.setPromptText("paste the token");
        var result = new Label(" ");
        result.setWrapText(true);
        var checked = new GitHubCheck[1];
        Runnable check = () -> {
            checked[0] = GitHubCheck.of(token.getText().trim());
            var admin = kind.getSelectionModel().getSelectedIndex() == 1;
            result.setText(checked[0].describe() + (checked[0].ok() && checked[0].canDeleteRepos() && !admin
                ? ". An everyday token should not be able to delete repositories: make a narrower one, or store this one as the admin token." : ""));
            result.setStyle("-fx-text-fill: " + (!checked[0].ok() ? ApprovalParts.RED : checked[0].canDeleteRepos() && !admin ? ApprovalParts.AMBER : ApprovalParts.GREEN) + ";");
        };
        Runnable save = () -> {
            if (checked[0] == null || !checked[0].ok()) { check.run(); if (!checked[0].ok()) return; }
            var id = settings.getAccount().get(accounts.getSelectionModel().getSelectedIndex()).getId();
            secrets.vault.put(Secrets.token(id, kind.getSelectionModel().getSelectedIndex() == 1), token.getText().trim());
            token.clear();
            changed.run();
            stage.close();
        };
        token.textProperty().addListener((o, a, b) -> checked[0] = null);
        var keys = new FKeys().bind(KeyCode.F2, "Check with GitHub", "#2e6da8", check).bind(KeyCode.ENTER, "Save", "#2e7d32", save)
            .bind(KeyCode.ESCAPE, "Cancel", "#777", stage::close);
        var root = new VBox(10, new Label("Account"), accounts, new Label("Which token"), kind, new Label("Token"), token, result, keys.bar,
            ApprovalParts.small("Arrows choose · F2 checks the token with GitHub · Enter checks and saves · Esc cancels", "#777"));
        root.setPadding(new Insets(14));
        root.setPrefWidth(560);
        var scene = new Scene(root);
        keys.install(scene);
        stage.setTitle("gitgate: set a GitHub token");
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.show();
        token.requestFocus();
    }

    private TokenDialog() {}
}
