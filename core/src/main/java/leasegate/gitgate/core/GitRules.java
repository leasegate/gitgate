package leasegate.gitgate.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import leasegate.core.access.*;

import static leasegate.core.access.ScopeLine.*;

/// gitgate's rules on top of leasegate's. Reading earns a long approval, pushing an ordinary one,
/// and ADMIN -- what cannot be undone -- needs the passphrase every time and lasts half an hour.
public final class GitRules implements LeaseRules<GitScope> {

    static final int ADMIN_CAP = 30, READ_FLOOR = 240;

    final Settings settings;

    public GitRules(Settings settings) { this.settings = settings; }

    @Override public String product() { return Places.PRODUCT; }

    @Override public DurationCaps.Result capByScope(DurationCaps.Result base, GitScope s) {
        if (s.tier() == Tier.ADMIN && base.minutes() > ADMIN_CAP)
            return new DurationCaps.Result(ADMIN_CAP, "an admin request (" + s.detail() + ")");
        if (s.tier() == Tier.READ && base.minutes() > 0 && base.minutes() < READ_FLOOR)
            return new DurationCaps.Result(READ_FLOOR, "a read-only request (raised from " + base.minutes() + "m, " + base.boundBy() + ")");
        return base;
    }

    @Override public int keyedMaxMinutes(GitScope s) { return s.tier() == Tier.ADMIN ? ADMIN_CAP : 240; }

    @Override public int riskBase(GitScope s) { return s.tier().ordinal(); }

    @Override public boolean needsPassphrase(GitScope s) { return s.tier() == Tier.ADMIN || s.account() == null; }

    @Override public List<ScopeLine> describe(GitScope s) {
        var out = new ArrayList<ScopeLine>();
        out.add(s.account() == null ? wide("account: ANY") : narrow("account: " + who(s.account())));
        out.add(switch (s.tier()) {
            case READ -> narrow("can: read (fetch, clone, view)");
            case WRITE -> caution("can: write (push, create pull requests and releases, edit issues)");
            case ADMIN -> wide("can: ADMIN -- this one: " + s.detail() + ". Admin requests cannot be undone.");
        });
        if (s.detail() != null && s.tier() != Tier.ADMIN) out.add(note("this request: " + s.detail() + " (the approval covers every repository of the account)"));
        return out;
    }

    String who(String id) { return settings.byId(id).map(a -> a.getId() + " (" + a.getName() + ")").orElse(id); }

    @Override public ScopeLine summary(GitScope s) {
        var text = (s.account() == null ? "any account" : who(s.account())) + "  ·  " + s.tier().word;
        return new ScopeLine(text, s.tier() == Tier.ADMIN ? Tone.WIDE : s.tier() == Tier.WRITE ? Tone.CAUTION : Tone.NARROW);
    }

    @Override public void writeScope(ObjectNode n, GitScope s) {
        if (s.account() != null) n.put("account", s.account());
        n.put("tier", s.tier().word);
    }

    @Override public GitScope readScope(JsonNode n) {
        return new GitScope(n.hasNonNull("account") ? n.get("account").asText() : null, Tier.of(n.path("tier").asText("read")), null, null);
    }

    @Override public GitScope stored(GitScope s) { return new GitScope(s.account(), s.tier(), null, null); }
}
