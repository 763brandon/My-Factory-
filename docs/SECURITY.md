# Security model

## What this app protects

**Your API keys.** Encrypted with AES-256-GCM under a key generated in the
Android keystore, which on most devices from Android 7 onwards is backed by
hardware. Key material never enters the app's address space; the keystore
returns a handle. Only the IV and the ciphertext land in preferences.

If the keystore is unusable, which happens on a few damaged vendor ROMs, the
key is not stored at all and Settings says why. There is no plaintext
fallback, because a key silently stored in the clear is worse than a key you
have to retype.

**Your files, from the agent.** Every path an agent tool touches goes through
`WorkspaceFs.resolve`, which canonicalises the path and checks it against the
canonical project root. That closes `../` traversal and symlinks that point out
of the tree in one check, because `canonicalFile` resolves both.

**Your device, from a rootfs.** The bootstrap downloads, verifies the SHA-256,
and only then extracts. A mismatch deletes the file. The tar extractor refuses
any entry whose resolved path would land outside the destination, which is the
"tar slip" class of bug, and stops at a byte budget rather than filling your
storage.

**Your project, from the preview pane.** The WebView is restricted to loopback
by parsed host, not by string prefix, so `http://127.0.0.1.evil.example/` is
refused. File and content URL access are off, no JavaScript bridge is
installed, and mixed content is blocked.

## What this app does not protect

**PRoot is not a sandbox.** It rewrites syscall paths using ptrace so an
unprivileged process sees a different filesystem layout. It does not confine a
hostile program, and a command run inside it has the same Android permissions
the app does. Anything claiming otherwise is wrong.

The boundary is your approval. Every command is shown to you in full before it
runs, and nothing runs on an implicit yes.

**The agent's judgement.** A model can be argued into proposing something
harmful by content it reads, including a file in your own project. That is why
the approval gate shows you the diff and the command rather than a summary of
them, and why "allow for this session" is scoped to one tool and one session
and can be switched off entirely in Settings.

**A compromised device.** Root, a malicious keyboard or a screen recorder all
sit below anything an app can do.

## Data flow

Your code and your keys go to exactly one place: the endpoint you configured.
The chat screen names that host on every screen so it is never ambiguous.

There is no analytics endpoint in this codebase. The diagnostic log is off by
default and, when on, writes a file on the device that you can read and choose
to attach to a bug report. Backup and device transfer are excluded wholesale in
`data_extraction_rules.xml`, so conversation history and file paths do not
reach a cloud backup you did not ask for.

Cleartext HTTP is disabled everywhere except loopback, which is where a
self-hosted model and your own dev server live and where TLS would add nothing.

## The activity log

Every file change, command execution, network request, checkpoint and approval
decision is appended to a local log you can read in Settings. It is a privacy
feature, not a debugging one: it is the answer to "what did this app actually
do".

## Reporting a vulnerability

Open an issue for anything already public. For something not yet public, please
open a GitHub security advisory on the repository rather than a public issue.

Please include the detection notes from Settings. On unusual hardware they are
usually most of the diagnosis.
