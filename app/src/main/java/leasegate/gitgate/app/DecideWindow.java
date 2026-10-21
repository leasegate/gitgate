package leasegate.gitgate.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import leasegate.gitgate.core.Settings;
import leasegate.frame.*;
import leasegate.ui.ApprovalParts;

/// "Which account is this repository?" -- shown while a git command waits in a terminal, when
/// nothing decides it (no rule names the repository) or the sources disagree. The human sees the
/// folder, its remotes, its last authors and what each rule says, picks the account and whether
/// its commits are anonymous, and the answer is written into the repository so it is never asked
/// again. Keys are ignored for the first two seconds, like every gate window, so a keystroke meant
/// for the terminal does not answer it.
final class DecideWindow {

    record Request(String repo, String remotes, String why, List<String> sources, List<String> authors, String suggested, String tool) {}

    record Choice(String account, boolean anonymous) {}

    static Optional<Choice> ask(Brand brand, Settings settings, Request r) {
        var answer = new CompletableFuture<Optional<Choice>>();
        Platform.runLater(() -> show(brand, settings, r, answer));
        return answer.join();
    }

    static void show(Brand brand, Settings settings, Request r, CompletableFuture<Optional<Choice>> answer) {
        var frame = new GateFrame(brand, "Which account is this repository?").onClose(() -> answer.complete(Optional.empty()));
        var stage = frame.stage;
        var accounts = settings.getAccount();
        var list = new ListView<String>(FXCollections.observableArrayList(accounts.stream()
            .map(a -> a.getId() + "  ·  " + a.getName() + "  ·  commits as " + a.getUserName() + " <" + a.getUserEmail() + ">").toList()));
        list.setPrefHeight(Math.min(150, 30 + accounts.size() * 26));
        var pre = 0;
        for (var i = 0; i < accounts.size(); i++) if (accounts.get(i).getId().equalsIgnoreCase(String.valueOf(r.suggested()))) pre = i;
        list.getSelectionModel().select(pre);
        var anon = new CheckBox("anonymous commits: " + settings.getAnonymous().getUserName() + " <" + settings.getAnonymous().getUserEmail() + ">  (the account still pushes)");
        var armLine = ApprovalParts.small(" ", ApprovalParts.AMBER);

        var arming = new Arming[1];
        Runnable decide = () -> {
            if (arming[0] == null || !arming[0].armed) return;
            var i = list.getSelectionModel().getSelectedIndex();
            if (i < 0) return;
            answer.complete(Optional.of(new Choice(accounts.get(i).getId(), anon.isSelected())));
            stage.close();
        };
        Runnable cancel = () -> { answer.complete(Optional.empty()); stage.close(); };
        Runnable toggle = () -> { if (arming[0] != null && arming[0].armed) anon.setSelected(!anon.isSelected()); };
        var fkeys = new FKeys().bind(KeyCode.F2, "This account", ApprovalParts.GREEN, decide)
            .bind(KeyCode.F4, "Anonymous on/off", "#5b3fa0", toggle)
            .bind(KeyCode.ESCAPE, "Don't decide (the command fails)", "#777", cancel);

        var info = new VBox(4,
            ApprovalParts.line("repository: " + r.repo(), "#222"),
            ApprovalParts.small("remotes: " + (r.remotes() == null || r.remotes().isBlank() ? "(none)" : r.remotes()), ApprovalParts.GREY),
            ApprovalParts.line("why I'm asking: " + r.why(), ApprovalParts.AMBER));
        if (!r.sources().isEmpty()) info.getChildren().add(ApprovalParts.small("the rules: " + String.join("  ·  ", r.sources()), ApprovalParts.GREY));
        info.getChildren().add(ApprovalParts.small("last authors: " + (r.authors().isEmpty() ? "(no commits yet)" : String.join("  ·  ", r.authors())), ApprovalParts.GREY));
        info.getChildren().add(ApprovalParts.small("asked by: " + r.tool() + ", waiting in a terminal", ApprovalParts.GREY));

        var root = new VBox(10, info, list, anon, armLine, fkeys.bar,
            ApprovalParts.small("Arrows choose · F2 records it in the repository (asked once) · F4 anonymous · Esc leaves it undecided", "#777"));
        root.setPadding(new Insets(14));
        root.setPrefWidth(720);
        var scene = frame.scene(root);
        fkeys.install(scene);
        stage.setAlwaysOnTop(true);
        stage.setOnHidden(e -> answer.complete(Optional.empty()));

        arming[0] = new Arming(fkeys.bar.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).toList(), armLine);
        arming[0].guard(scene);
        stage.show();
        stage.toFront();
        list.requestFocus();
    }

    private DecideWindow() {}
}
