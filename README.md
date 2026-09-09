# Forge

An AI coding IDE that runs on your phone. Chat with a coding agent, edit files,
review diffs, run Linux commands and preview a web app, on the handset, with no
root.

Forge is an independent reimplementation inspired by
[techjarves/Mobile-Harness](https://github.com/techjarves/Mobile-Harness). It
exists to fix four specific limitations of that project: it is arm64-only,
tied to a single AI vendor, heavy on memory, and English-only.

**Status: early. Read the capability matrix below before you rely on anything.**

---

## What is different here

| | Mobile-Harness | Forge |
|---|---|---|
| CPU support | arm64-v8a only | armeabi-v7a, arm64-v8a, x86, x86_64 |
| AI provider | one vendor, hard-wired | Anthropic, OpenAI, Gemini, OpenRouter, or any OpenAI-compatible endpoint |
| Low-RAM devices | not a design goal | LIGHTWEIGHT tier, tested down to a 2 GB profile |
| Languages | English | English, French, Swahili, Arabic, with RTL and a per-app language picker |
| Missing capability | assumed present | detected, and the app degrades instead of crashing |

The 32-bit target is the point. Entry-level Transsion handsets (Tecno, Infinix,
itel) are the phones most people in West and East Africa actually own, and an
arm64-only build tells them "app not compatible" at the store.

---

## Capability matrix

Honest is the only useful kind. **Verified** means it was exercised by an
automated test or a build artifact was inspected in this repository.
**Untested** means the code is written and compiles but has not been run on
that configuration.

### By component

| Component | Status | Evidence |
|---|---|---|
| Diff engine, patch apply, unified diff parse | Verified | 38 unit tests, including a randomised property test over 300 input pairs |
| Per-hunk review and revert against a checkpoint | Verified | 12 unit tests, including that a non-applying hunk is skipped rather than corrupting the file |
| Capability detection and tier routing | Verified | 15 unit tests across ABI, RAM, core count, storage and vendor flags |
| Provider adapters and streaming | Verified | 27 unit tests replaying recorded byte streams at hostile chunk boundaries |
| Agent loop and approval gate | Verified | 17 unit tests, including that no approval-gated tool runs without consent |
| Workspace path safety | Verified | 17 unit tests covering traversal, symlink escape, absolute paths, null bytes |
| Tar extractor, checkpoints, preview server | Verified | 33 unit tests, including tar-slip, loopback-only binding, and stop-then-restart |
| Syntax highlighter and loopback URL guard | Verified | 14 unit tests |
| Android 7 API floor in `:core` | Verified | compiled classes scanned for `java.time` and `java.nio.file` |
| Native PTY builds for all four ABIs | Verified | `.so` inspected per ABI; all six JNI symbols exported |
| Per-ABI APK packaging | Verified | each split APK contains only its own `libforgepty.so` |
| Terminal running a real shell | Untested | instrumented tests written, no device in this environment |
| PRoot rootfs bootstrap | Untested, and not provisioned | see [Linux runtime](#the-linux-runtime) |
| WebView preview rendering | Untested | logic tested; rendering needs a device |
| Keystore encryption | Untested | instrumented tests written; the keystore only exists on a device |

Total: **174 automated tests, all passing** (160 in `:core`, 14 in `:app`).
Android lint reports no correctness, internationalisation or accessibility
findings; what remains are version-currency notices on pinned dependencies.

### By tier

Tiers are chosen at runtime from RAM, CPU cores, ABI and free storage. The OS
floor is uniform at Android 7, so tiers exist only to protect weak hardware.

| Feature | FULL (3 GB+) | LIGHTWEIGHT (under 3 GB) |
|---|---|---|
| Agent chat with streaming | Yes | Yes |
| File browser and editor | Yes, 4 MB file limit | Yes, 512 KB file limit |
| Live syntax highlighting | Yes | Off while typing |
| Diff review, per hunk | Yes | Yes |
| Revert individual hunks since a checkpoint | Yes | Yes |
| Checkpoints and rollback | Yes | Yes |
| Agent turns per request | 25 | 12 |
| Linux userspace | Ubuntu 22.04 under PRoot | Alpine under PRoot |
| Terminal with a real PTY | Yes | Yes |
| Web preview | Yes | Yes above 1.4 GB RAM |

Every reduction is recorded and shown verbatim in Settings, so an odd device
produces a useful bug report rather than a shrug.

### When something is missing

Nothing here is a crash. Each is a state the UI renders and explains.

| Missing | What happens |
|---|---|
| PRoot binary for this ABI | Falls back to BusyBox: file commands work, package managers do not |
| BusyBox as well | No local execution. The agent still reads and edits files through the JVM |
| Native PTY library | Terminal is off. Everything else is unaffected |
| System WebView | Preview is off. Everything else is unaffected |
| Under 64 MB free storage | No Linux runtime, agent still usable |
| Android keystore unusable | The key is not stored at all, and Settings says why. Never a plaintext fallback |

---

## Security and privacy

- **API keys** are encrypted with AES-256-GCM under a key held in the Android
  keystore, which on most devices from Android 7 is hardware-backed. Only the
  ciphertext and IV reach preferences. If the keystore refuses, the key is not
  saved; there is no plaintext fallback.
- **Nothing is uploaded.** There is no analytics endpoint in this codebase. The
  diagnostic log is off by default and writes only to the device.
- **PRoot is not a security boundary.** It rewrites syscall paths with ptrace so
  an unprivileged process sees a different filesystem layout. It does not
  contain a hostile program. The boundary is your approval.
- **Every write and every command needs your consent**, shown as a diff or as
  the exact command line. Nothing runs on an implicit yes; dismissing the sheet
  is a decline.
- **Destinations are visible.** The chat screen names the host every request
  goes to. Cleartext is off everywhere except loopback, which is where a
  self-hosted model and your dev server live.
- **The activity log** records every file change, command and network request,
  and you can read it in Settings.
- **Backup and device transfer are excluded wholesale**, so conversation
  history and file paths do not end up in a cloud backup you did not ask for.

See [docs/SECURITY.md](docs/SECURITY.md) for the threat model.

---

## AI providers

No provider is bundled, none is mandatory, and no key ships in this repository.

| Provider | Endpoint | Key |
|---|---|---|
| Anthropic | `https://api.anthropic.com` | required |
| OpenAI | `https://api.openai.com/v1` | required |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta` | required |
| OpenRouter | `https://openrouter.ai/api/v1` | required |
| OpenAI-compatible | yours | optional |

The last row covers Ollama, LM Studio, llama.cpp, vLLM and corporate gateways.
Point it at `http://127.0.0.1:11434/v1` for a local Ollama and leave the key
blank.

Adding a provider means implementing `AiProvider` and registering it in
`AppContainer`. Nothing else changes: the agent loop, the UI and the settings
screen all work against that one interface.

---

## The Linux runtime

This is the part to read before filing an issue about the terminal.

Forge builds `libforgepty.so` from source in this repository, for all four
ABIs. That gives you a real pseudo-terminal, which is what makes window
resizing, job control and ncurses work.

It does **not** bundle PRoot or BusyBox, and the rootfs catalog ships
placeholder checksums. Two reasons:

1. A rootfs is executable code that runs on your device. Publishing a checksum
   for a file this repository does not host would be a promise it cannot keep,
   so the bootstrap refuses to download an unverified image and the UI says so.
2. PRoot and BusyBox are separately licensed projects with their own build
   requirements. Vendoring prebuilt binaries into a repository is how supply
   chains rot.

[docs/NATIVE_BINARIES.md](docs/NATIVE_BINARIES.md) has the build instructions
and `tools/refresh-rootfs-catalog.sh` regenerates the catalog against a mirror
you control. Until then the app runs in BusyBox or agent-only mode and tells
you which.

---

## Build from source

Requirements: JDK 17 or newer, Android SDK with platform 35 and build-tools 35,
and NDK 27 with CMake 3.22 for the native terminal.

```bash
git clone https://github.com/763brandon/My-Factory-.git
cd My-Factory-
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

./gradlew :core:test          # 160 tests, no device or SDK needed
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug  # per-ABI plus universal APKs
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease  # AAB
```

Without the NDK, build with `-Pforge.buildNative=false`. The app still builds
and runs; the terminal reports that its library is missing and everything else
works.

Instrumented tests need a device or emulator, and the 32-bit path is the
higher-risk one, so run it explicitly:

```bash
./gradlew :runtime-linux:connectedDebugAndroidTest   # on an armeabi-v7a AVD
./gradlew :app:connectedDebugAndroidTest
```

Release APKs come to about 1.9 MB per ABI with R8 on.

To sign a release, create `keystore.properties` in the project root (it is
git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

---

## Architecture

```
app             Compose Material 3 UI, navigation, tier routing
core            agent loop, providers, diff, files, checkpoints  (pure Kotlin/JVM)
data            Room persistence
runtime-linux   JNI PTY bridge, PRoot bootstrap                  (C++ and Kotlin)
```

`:core` is deliberately not an Android module. Everything that does not need a
device lives there and runs under `./gradlew :core:test` on any machine, which
is why the test count is what it is. Its one constraint is that it must not
touch an API missing on Android 7, and `ApiLevelCompatibilityTest` enforces
that by scanning the compiled classes for `java.time` and `java.nio.file`.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: keep `:core` free of Android
imports, keep the approval gate in front of every write and command, and do not
add a capability claim to this README without a test behind it.

---

## Licence and affiliation

MIT. See [LICENSE](LICENSE).

**Not affiliated with, endorsed by, or sponsored by any AI provider.** Anthropic,
OpenAI, Google, OpenRouter, Ollama and LM Studio are trademarks of their
respective owners, used here only to name the endpoints this app can be pointed
at. You supply your own key and your own account, and your use of any provider
is governed by that provider's own terms.
