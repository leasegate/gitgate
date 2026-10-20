# gitgate

**git and the GitHub CLI, with the right identity for every folder and no credential lying around.**

On a workstation with more than one GitHub identity, two things are usually true at once: something has to pick the right SSH key and token for each repository, and those keys and tokens sit in plain files and environment variables that every program can read. With AI agents and automated tools running commands on the same machine, the second part is no longer acceptable: a token that can delete repositories should not be one `cat` away from a prompt injection.

gitgate is a [leasegate](https://github.com/leasegate/leasegate) gate for git and GitHub:

- **The official git, unchanged.** `gitgate setup` makes the real `git` and Windows' own `ssh` work through the gate; no program of gitgate's stands between them. It writes gitgate's own git config and ssh config and adds one line to each of yours (`[include]` in `~/.gitconfig`, `Include` at the top of `~/.ssh/config`); your own entries there are never touched, and gitgate rewrites its files whenever `settings.toml` changes. `gitgate setup --undo` removes the two lines.
- **Identity by organisation and folder, from git itself.** gitgate's git config carries `includeIf "hasconfig:remote.*.url:git@github.com:<org>/**"` for every organisation of an account and `includeIf "gitdir/i:<folder>/"` for every folder, so git picks the account's name and email on its own (git 2.36 or newer).
- **Each repository remembers its account.** Four sources are compared: what the repository has recorded, the folder rules (`paths`, `pathRegex`), the GitHub organisation its remotes point at (`orgs`), and (for gh) the account an alias is pinned to. When they agree, the answer is written into the repository's own `.git/config` (`gitgate.account`, `gitgate.identity`, `user.name`, `user.email`), which wins over everything else. When they disagree, or nothing names the repository, gitgate does not guess: gitgate's pre-commit hook asks the human in the tray which account it is, and the terminal waits. git reads its identity before a hook runs, so when a commit was started under another identity, that one commit is stopped once and the next one is right. Without the tray, the hook stops the commit and says how to decide: `gitgate assign <account>`.
- **Anonymous repositories.** A repository can be marked anonymous: its commits carry a deliberately meaningless identity (`[anonymous]`, default `User <user@example.com>`), and the account still decides whose key pushes it.
- **No commit leaves under the wrong name.** Before a push, every commit not yet on a remote must carry the pushing account's identity as author and committer (its `userEmail` or one of its `alsoEmails`); otherwise the push is refused with the command that rewrites those commits. A commit made under one identity and pushed by another puts that identity on the repository for good.
- **SSH keys never leave the gate.** The gitgate tray holds them in an encrypted vault and is an ssh agent on its own named pipe; gitgate's ssh config points `github.com` at it and git runs Windows' OpenSSH (`core.sshCommand`, a bare path, so no shell). The agent reads the ssh process's own command line -- `git-receive-pack 'org/repo'` is a push, `git-upload-pack` a fetch -- and lists only the key of the account that owns `org`, refusing a signature by any other account's key. An organisation in no account's `orgs` is read with the default account's key; pushing to one asks once, and the answer is kept in `orgs.json`.
- **GitHub tokens are released per run.** The gh proxy classifies the command (read, write or admin), asks the tray for the account's token for that tier, and hands it to the one gh process it starts, in its environment. Commands whose job is to print or replace the token (`gh auth token`, `auth status --show-token`, `auth login`) are refused before the gate is asked. What cannot be undone or runs other code with the token in reach -- `repo delete`, `repo edit`, secrets, keys, `api -X DELETE`, aliases, extensions -- is ADMIN: always the passphrase, at most thirty minutes, and optionally a second, more powerful token that is used for nothing else.
- **Each gate looks like itself.** Its windows open with a wide band in its own colour, its icon large and its name, so a gitgate request is never mistaken for another gate's. For the first two seconds every key is ignored, and approving takes F2, never a letter: a word typed into another window cannot approve anything.
- **Every approval is a leasegate approval:** the window shows the requesting process and its whole chain (`ssh.exe` under `git.exe` under `bash.exe` under an agent), the human picks how far up it reaches and for how long, and it ends when that process exits. One approval to push as an account covers all of its repositories until it ends.
- **A push guard** (gitgate's pre-push hook). The repository's account must own the organisation being pushed to; an account can name `forbidden` terms (regular expressions), and every commit not yet on a remote -- messages and patches -- is searched for them. A push to a public repository cannot be taken back; this is the last moment a word that must not be there can be caught. `GITGATE_ALLOW_AUTHOR=1` and `GITGATE_ALLOW_FORBIDDEN=1` let a push through deliberately. gitgate's hooks run the repository's own hook of the same name afterwards.
- **`gh.exe`** stays a stand-in for the real gh, because gh takes a token only from its environment: it picks the account like git does and hands the token to the one gh process. A copy named `gh<account>.exe` runs with that account fixed. `gh config set` is ADMIN: gh runs its configured pager, editor and browser with the token in its environment.
- **A new repository is plain git and gh:** `git init`, commit, then `gh repo create <org>/<name> --private --source=. --push`. With gh's `git_protocol` set to `ssh`, the remote is SSH from the start (an https remote would need a credential helper, which an agent's shell cannot answer), and the hooks decide the account and guard the first push.

## Settings

`~/leasegate/gitgate/settings.toml`, and no secrets in it:

```toml
windows = 'glow'           # optional: 'glow' (default) or 'native', how the tray's windows are framed
resizableWindows = 'glow'  # optional: 'native' keeps Windows' snap and edge resizing for the resizable ones
sharedAuthors = []         # optional: addresses any account's commits may carry besides its own (e.g. a coding agent's)
ignore = []                # optional: patterns gitgate setup writes into git's global ignore file
forbiddenPaths = []        # optional: file paths (regexes) never pushed under any account; deliberately anyway:
                           #   one push: GITGATE_ALLOW_PATHS=1 git push; one repository: git config gitgate.allowPaths true
sshHosts = ['github.com']  # the hosts gitgate's agent serves; an ssh to any other host gets no key from it

[binaries]
git = 'C:\tools\git\cmd\git.exe'
gh = 'C:\tools\gh\bin\gh.exe'
ssh = 'C:/Windows/System32/OpenSSH/ssh.exe'

[output]
accountLine = 'stderr'     # stdout | stderr | none
noArgs = 'stdout'

[newRepo]
defaultAccount = 'work'    # the account for reading from an organisation no account's `orgs` lists

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
forbiddenPaths = ['(^|/)notes/']  # optional: more paths never pushed under this account
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

JDK 25, Maven, and [leasegate](https://github.com/leasegate/leasegate) installed first (`mvn install` there). Then `mvn install` here; `mvn -Djrexe -Djr.installDir=<a folder on PATH> package` builds the exes with [jr](https://github.com/jarrunner/jr) (gitgate, gh). Then start the tray and run `gitgate setup` once.

Windows only for now: the agent pipe and the process-chain reading are Windows code paths in leasegate, and gitgate relies on Windows' OpenSSH reaching an agent on a named pipe (`IdentityAgent=//./pipe/<name>`, forward slashes -- the backslash form is not accepted).

## License

Apache License 2.0.
