package leasegate.gitgate.app;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import leasegate.core.ConsumerServer;
import leasegate.core.LeaseEngine;
import leasegate.core.Outcome;
import leasegate.core.Replies;
import leasegate.core.access.Handshake;
import leasegate.core.access.ProcessTree;
import leasegate.gitgate.core.*;
import leasegate.frame.*;

import static leasegate.core.Replies.M;

/// One connection on gitgate's own pipe. `ping` and `status` are open to any process of this user
/// and name no secret; `token` releases an account's GitHub token for one tier, after the engine
/// has found a grant or asked the human.
final class ControlHandler {

    final LeaseEngine<GitScope> engine;
    final Secrets secrets;
    final Settings settings;
    final leasegate.frame.Brand brand;

    ControlHandler(LeaseEngine<GitScope> engine, Secrets secrets, Settings settings, leasegate.frame.Brand brand) {
        this.engine = engine;
        this.secrets = secrets;
        this.settings = settings;
        this.brand = brand;
    }

    void handle(ConsumerServer.Accepted a) {
        var raw = ProcessTree.rawChain(a.peer().pid());
        var io = a.io();
        try (io) {
            var first = io.readFrame();
            var hs = first == null ? null : Handshake.extract(first).orElse(null);
            if (hs == null) { io.writeFrame(Replies.denied("not a gitgate request", "send {\"gate\":{\"op\":..}}")); return; }
            io.writeFrame(switch (hs.op()) {
                case "ping" -> Replies.wrap(M.createObjectNode().put("ok", true).put("product", Places.PRODUCT));
                case "status" -> status();
                case "token" -> token(a, raw, hs);
                case "decide" -> decide(hs);
                default -> Replies.denied("unknown op " + hs.op(), "use ping, status, token or decide");
            });
        } catch (RuntimeException e) {
            System.err.println("gitgate: request from pid " + a.peer().pid() + " failed: " + e.getMessage());
        }
    }

    String token(ConsumerServer.Accepted a, List<ProcessTree.RawLink> raw, Handshake hs) {
        var account = hs.body().path("account").asText(null);
        if (account == null || settings.byId(account).isEmpty()) return Replies.denied("unknown account " + account, "the accounts are in " + Places.settings());
        Tier tier;
        try { tier = Tier.of(hs.body().path("tier").asText("write")); } catch (IllegalArgumentException e) { return Replies.denied(e.getMessage(), "read, write or admin"); }
        var admin = tier == Tier.ADMIN;
        if (secrets.tokenFor(account, admin).isEmpty())
            return Replies.denied("gitgate holds no GitHub token for account " + account, "gitgate tray menu: Set GitHub token");
        var detail = hs.body().path("detail").asText("gh");
        var label = (hs.app() != null ? hs.app() : "gh") + ": " + detail;
        return switch (engine.attest(a.peer(), raw, new GitScope(account, tier, detail, hs.minutes()), label)) {
            case Outcome.Refused(var error, var hint) -> Replies.denied(error, hint);
            case Outcome.Allowed(var grant) -> Replies.approved("attested", grant.expiresAt(),
                M.createObjectNode().put("account", account).put("tier", tier.word).put("token", secrets.tokenFor(account, admin).orElseThrow()), null);
        };
    }

    /// Which account a repository is: releases nothing, so any process may ask -- the answer comes
    /// from the human in the window, and the asking git command writes it into the repository.
    String decide(Handshake hs) {
        var b = hs.body();
        var sources = new java.util.ArrayList<String>();
        b.path("sources").forEach(n -> sources.add(n.asText()));
        var authors = new java.util.ArrayList<String>();
        b.path("authors").forEach(n -> authors.add(n.asText()));
        var r = new DecideWindow.Request(b.path("repo").asText("?"), b.path("remotes").asText(""), b.path("why").asText(""),
            sources, authors, b.path("suggested").asText(null), hs.app() != null ? hs.app() : "git");
        return DecideWindow.ask(brand, settings, r)
            .map(c -> Replies.wrap(M.createObjectNode().put("ok", true).put("account", c.account()).put("anonymous", c.anonymous())))
            .orElseGet(() -> Replies.denied("not decided: the window was closed", "decide it with gitgate assign <account> in the repository, or run the command again"));
    }

    String status() {
        var g = M.createObjectNode().put("ok", true).put("product", Places.PRODUCT);
        var arr = g.putArray("accounts");
        for (var acc : settings.getAccount()) {
            ObjectNode n = arr.addObject().put("id", acc.getId()).put("name", acc.getName()).put("ssh", acc.viaGate() ? "gate" : "key file")
                .put("token", secrets.tokenFor(acc.getId(), false).isPresent()).put("adminToken", secrets.vault.get(Secrets.token(acc.getId(), true)).isPresent());
            secrets.keys.get(acc.getId()).ifPresent(k -> n.put("key", k.fingerprint()).put("publicKey", k.publicLine()));
        }
        g.put("approvals", engine.activeGrants().size());
        return Replies.wrap(g);
    }
}
