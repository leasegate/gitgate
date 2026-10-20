package leasegate.gitgate.app;

import java.util.List;
import java.util.Optional;

import leasegate.core.LeaseEngine;
import leasegate.core.Outcome;
import leasegate.core.access.ProcessTree;
import leasegate.core.win.PeerIdentity;
import leasegate.frame.Brand;
import leasegate.gitgate.core.*;
import leasegate.sshagent.AgentKey;
import leasegate.sshagent.SignPolicy;

/// The agent's side of "which account": an ssh process is offered the key of exactly one account --
/// the one owning the GitHub organisation in its own command line (`git-receive-pack 'org/repo'`),
/// so the official git and ssh need nothing from gitgate but the agent pipe. An organisation no
/// account names is read with the default account's key; pushing to one asks the human once, and
/// the answer is remembered. A signature is then approved by the engine, from a grant or by asking,
/// and refused outright for a key of any other account.
final class AgentPolicy implements SignPolicy {

    final LeaseEngine<GitScope> engine;
    final Secrets secrets;
    final Settings settings;
    final Brand brand;

    AgentPolicy(LeaseEngine<GitScope> engine, Secrets secrets, Settings settings, Brand brand) {
        this.engine = engine;
        this.secrets = secrets;
        this.settings = settings;
        this.brand = brand;
    }

    /// gitgate's keys are GitHub keys: an ssh pointed at this agent for any other host gets none.
    boolean forGitHub(String sshCommandLine) {
        var host = SshTarget.host(sshCommandLine);
        return host != null && settings.getSshHosts().stream().anyMatch(h -> h.equalsIgnoreCase(host));
    }

    @Override public List<AgentKey> offer(List<AgentKey> all, PeerIdentity peer, List<ProcessTree.RawLink> chain, String sshCommandLine) {
        if (!forGitHub(sshCommandLine)) {
            System.err.println("gitgate: no key for " + SshTarget.host(sshCommandLine) + ": gitgate's keys are for " + settings.getSshHosts());
            return List.of();
        }
        var account = account(SshTarget.of(sshCommandLine));
        if (account.isEmpty()) return List.of();
        return all.stream().filter(k -> secrets.keys.owner(k).map(o -> o.equalsIgnoreCase(account.get().getId())).orElse(false)).toList();
    }

    @Override public boolean allow(Request r) {
        var owner = secrets.keys.owner(r.key());
        if (owner.isEmpty() || !forGitHub(r.sshCommandLine())) return false;
        var target = SshTarget.of(r.sshCommandLine());
        var account = account(target);
        if (account.isPresent() && !account.get().getId().equalsIgnoreCase(owner.get())) {
            System.err.println("gitgate: refused " + owner.get() + "'s key for " + target.detail() + ", which belongs to account " + account.get().getId());
            return false;
        }
        var scope = new GitScope(owner.get(), target.tier(), target.detail(), null);
        return engine.attest(r.peer(), r.chain(), scope, target.detail()) instanceof Outcome.Allowed;
    }

    /// The account this ssh is for. An organisation of nobody's: a read takes the default account; a
    /// push asks, once.
    Optional<Account> account(SshTarget target) {
        var org = target.org();
        var known = OrgMemory.account(settings, org);
        if (known.isPresent()) return known;
        var fallback = settings.byId(settings.getNewRepo().getDefaultAccount()).or(() -> settings.getAccount().stream().findFirst());
        if (org == null || target.tier() == Tier.READ) return fallback;
        var r = new DecideWindow.Request("github.com/" + target.repo(), "", "nobody's GitHub organisation: " + org + " is in no account's orgs",
            List.of(), List.of(), fallback.map(Account::getId).orElse(null), "ssh (a push)");
        var choice = DecideWindow.ask(brand, settings, r);
        choice.ifPresent(c -> OrgMemory.remember(org, c.account()));
        return choice.flatMap(c -> settings.byId(c.account()));
    }
}
