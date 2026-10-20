package leasegate.gitgate.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import leasegate.gitgate.core.Places;
import leasegate.gitgate.core.Tier;
import leasegate.transport.LeaseDeniedException;
import leasegate.transport.Links;

/// The CLI's side of the gitgate tray: is it running, and may this process have an account's
/// token for this tier. Asking for a token blocks while a human looks at the approval window.
final class Gate {

    static final ObjectMapper M = new ObjectMapper();

    /// Whether the tray answers at all.
    static boolean running() {
        try (var io = Links.open(Places.PRODUCT, Places.runDir(), Places.PIPE)) {
            io.writeFrame(frame(M.createObjectNode().put("v", 2).put("op", "ping")));
            return io.readFrame() != null;
        } catch (LeaseDeniedException e) { return false; }
    }

    /// The token, or LeaseDeniedException with the gate's own reason and hint.
    static String token(String account, Tier tier, String detail, String app) {
        try (var io = Links.open(Places.PRODUCT, Places.runDir(), Places.PIPE)) {
            io.writeFrame(frame(M.createObjectNode().put("v", 2).put("op", "token").put("app", app)
                .put("account", account).put("tier", tier.word).put("detail", detail)));
            var reply = reply(io.readFrame());
            if (!reply.path("ok").asBoolean(false))
                throw new LeaseDeniedException(reply.path("error").asText("denied"), reply.path("hint").asText(null));
            return reply.path("token").asText();
        }
    }

    static JsonNode reply(String raw) {
        if (raw == null) throw new LeaseDeniedException("gitgate closed the connection", null);
        try { return M.readTree(raw).path("gate"); } catch (Exception e) { throw new LeaseDeniedException("unreadable reply from gitgate", null); }
    }

    static String frame(JsonNode body) { return M.createObjectNode().set("gate", body).toString(); }

    private Gate() {}
}
