package leasegate.gitgate.app;

import leasegate.core.LeaseEngine;
import leasegate.core.Outcome;
import leasegate.gitgate.core.GitScope;
import leasegate.sshagent.SignPolicy;

/// Whether an ssh process may have an account's key sign for it: the account is whichever owns the
/// key asked for, the tier and repository come from the ssh command line, and the engine answers
/// from a grant or asks the human.
final class AgentPolicy implements SignPolicy {

    final LeaseEngine<GitScope> engine;
    final Secrets secrets;

    AgentPolicy(LeaseEngine<GitScope> engine, Secrets secrets) {
        this.engine = engine;
        this.secrets = secrets;
    }

    @Override public boolean allow(Request r) {
        var owner = secrets.keys.owner(r.key());
        if (owner.isEmpty()) return false;
        var target = SshTarget.of(r.sshCommandLine());
        var scope = new GitScope(owner.get(), target.tier(), target.detail(), null);
        return engine.attest(r.peer(), r.chain(), scope, target.detail()) instanceof Outcome.Allowed;
    }
}
