# gitgate

**git and the GitHub CLI, with the right identity for every folder and no credential lying around.**

On a workstation with more than one GitHub identity, two things are usually true at once: something has to pick the right SSH key and token for each repository, and those keys and tokens sit in plain files and environment variables that every program can read. With AI agents and automated tools running commands on the same machine, the second part is no longer acceptable: a token that can delete repositories should not be one `cat` away from a prompt injection.

gitgate is a [leasegate](https://github.com/leasegate/leasegate) gate for git and GitHub:

- **`git.exe` and `gh.exe` stand in front of the real ones** and pick the account for the repository. A one-line notice says which (`ACCOUNT(work,Work) ACTIVATEDBY(...)`, on stderr by default so scripts reading git's output are not disturbed).
- **Each repository remembers its account.** Four sources are compared: what the repository has recorded, the folder rules (`paths`, `pathRegex`), the GitHub organisation its remotes point at (`orgs`), and the account an alias is pinned to. When they agree, the answer is written into the repository's own `.git/config` (`gitgate.account`, `gitgate.identity`, `user.name`, `user.email`), so every git on the machine -- an IDE's, a shell's -- commits as the right identity from then on. When they disagree, or nothing names the repository, gitgate does not guess: a command that commits or talks to a remote waits while the tray asks the human which account it is (read-only commands are never held up). Without the tray, the command stops and says how to decide: `gitgate assign <account>`.
- **Anonymous repositories.** A repository can be marked anonymous: its commits carry a deliberately meaningless identity (`[anonymous]`, default `User <user@example.com>`), and the account still decides whose key pushes it.
- **No commit leaves under the wrong name.** Before a push, every commit not yet on a remote must carry the pushing account's identity as author and committer (its `userEmail` or one of its `alsoEmails`); otherwise the push is refused with the command that rewrites those commits. A commit made under one identity and pushed by another puts that identity on the repository for good.
- **SSH keys never leave the gate.** The gitgate tray holds them in an encrypted vault and is an ssh agent on its own named pipe. git is pointed at Windows' OpenSSH with only the account's *public* key file; ssh asks the agent to sign, and the agent asks the gate. The gate reads the ssh process's command line to see what it is about to do: `git-receive-pack 'org/repo'` is a push, `git-upload-pack` a fetch.
- **GitHub tokens are released per run.** The gh proxy classifies the command (read, write or admin), asks the tray for the account's token for that tier, and hands it to the one gh process it starts, in its environment. Commands whose job is to print or replace the token (`gh auth token`, `auth status --show-token`, `auth login`) are refused before the gate is asked. What cannot be undone or runs other code with the token in reach -- `repo delete`, `repo edit`, secrets, keys, `api -X DELETE`, aliases, extensions -- is ADMIN: always the passphrase, at most thirty minutes, and optionally a second, more powerful token that is used for nothing else.
- **Each gate looks like itself.** Its windows open with a wide band in its own colour, its icon large and its name, so a gitgate request is never mistaken for another gate's. For the first two seconds every key is ignored, and approving takes F2, never a letter: a word typed into another window cannot approve anything.
- **Every approval is a leasegate approval:** the window shows the requesting process and its whole chain (`ssh.exe` under `git.exe` under `bash.exe` under an agent), the human picks how far up it reaches and for how long, and it ends when that process exits. One approval to push as an account covers all of its repositories until it ends.
- **A push guard.** An account can name `forbidden` terms (regular expressions). Before a push, every commit not yet on a remote -- messages and patches -- is searched for them, and the push is refused on a hit. A push to a public repository cannot be taken back; this is the last moment a word that must not be there can be caught.
- **One-account aliases** (`gitwork`, `ghwork`, ...) for being explicit, built from the same jar with the account fixed.
- **`git1st`** makes the current folder a repository and gives it a home on GitHub: account, organisation, .gitignore template, visibility, then init, first commit, the push guard, `gh repo create`, an SSH remote (never https), first push.

## Settings

`~/leasegate/gitgate/settings.toml`, and no secrets in it:

```toml
windows = 'glow'           # optional: 'glow' (default) or 'native', how the tray's windows are framed
resizableWindows = 'glow'  # optional: 'native' keeps Windows' snap and edge resizing for the resizable ones

[binaries]
git = 'C:\tools\git\cmd\git.exe'
gh = 'C:\tools\gh\bin\gh.exe'
ssh = 'C:/Windows/System32/OpenSSH/ssh.exe'

[output]
accountLine = 'stderr'     # stdout | stderr | none
noArgs = 'stdout'

[newRepo]
defaultAccount = 'work'
gitignore = 'java'
gitignoreTemplates = 'C:\tools\gitignore-templates'

[anonymous]                # optional: the identity of repositories marked anonymous
userName = 'User'
userEmail = 'user@example.com'

[[account]]
id = 'work'
name = 'Work'
description = 'the work identity'
userName = 'worker'
userEmail = 'worker@example.org'
alsoEmails = ['worker@users.noreply.github.com']   # optional: other addresses this account's commits may carry
orgs = ['example-org']
paths = ['C:\code\example-org']
pathRegex = ['.*/example-org-.*/.*']
ssh = 'gate'               # or the path of a key file: the old, readable-by-anyone way
forbidden = ['internal-codename']
```

Write Windows paths in single quotes: in a double-quoted TOML string every backslash is an escape.

## Getting the credentials in

Start `gitgate.exe` (the tray). On first run it asks for a passphrase and shows a **recovery key once** -- write it down; it is the only way back in if the passphrase is forgotten. Then, from the tray menu:

- **SSH keys…** -- make a key inside gitgate (it never exists as a file) and add its public half on GitHub, or import an existing key file once and delete the file.
- **Set GitHub token…** -- paste a token; gitgate asks GitHub whose it is and whether it can delete repositories before storing it. Make the everyday token without `delete_repo`.

`gitgate status` shows what the tray holds (names and fingerprints only). `gitgate pubkey <account>` prints a public key.

## Repositories

- **Repositories and rules…** (tray menu) -- the rules, and every repository gitgate has recorded with its account; F3 moves the selected one to the next account, F4 marks it anonymous or not, F7 audits.
- `gitgate which [dir]` -- which account a repository is, and what each source says.
- `gitgate assign <account> [--anonymous] [dir]` -- record it by hand.
- `gitgate audit [dir...]` -- every repository whose history carries another account's identity, split into commits already pushed and commits still only local. Read-only.

## Building

JDK 25, Maven, and [leasegate](https://github.com/leasegate/leasegate) installed first (`mvn install` there). Then `mvn install` here; `mvn -Djrexe -Djr.installDir=<a folder on PATH> package` builds the exes with [jr](https://github.com/jarrunner/jr). Put that folder ahead of the real git on PATH.

Windows only for now: the agent pipe and the process-chain reading are Windows code paths in leasegate, and gitgate relies on Windows' OpenSSH reaching an agent on a named pipe (`IdentityAgent=//./pipe/<name>`, forward slashes -- the backslash form is not accepted).

## License

Apache License 2.0.
