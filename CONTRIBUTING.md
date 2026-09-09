# Contributing to Forge

Thank you for looking. This document is short because most of it is one idea:
the constraints this project exists to satisfy are load-bearing, so please
keep them.

## The four rules

**1. `:core` stays free of Android.**

`:core` is a plain Kotlin/JVM module. Everything that does not need a device
lives there, which is why 158 of the project's tests run on any machine in
about three seconds. If you find yourself importing `android.*` into `:core`,
define an interface there and implement it in `:app` or `:runtime-linux`
instead. `CapabilityDetector` and `DeviceProfile` are the pattern to copy.

**2. Nothing in `:core` may use an API missing on Android 7.**

That rules out `java.time`, `java.nio.file`, `java.util.stream` and
`Optional`. Use `java.io.File` and epoch millis. Desugaring is deliberately
off, because the 32-bit low-end build is the whole reason this project exists
and every kilobyte of dex costs something there.
`ApiLevelCompatibilityTest` enforces this by scanning the compiled classes, so
you will find out at test time rather than on a user's phone.

**3. Every write and every command stays behind the approval gate.**

A new tool that changes files or runs anything must set
`AgentTool.requiresApproval`, and its approval request must carry a preview:
a diff for a write, the exact command line for an execution. A yes/no box
without that is consent in name only.

`AgentLoopTest` has a test that fails if any tool in the mutating set forgets
the flag. Please do not delete it.

**4. Do not claim a capability the tests do not cover.**

The README's capability matrix distinguishes verified from untested, and that
distinction is the most valuable thing in the file. If you add a feature,
either add a test and move the row, or leave it in the untested column and say
so. Overclaiming is worse than a missing feature.

## Adding an AI provider

Implement `AiProvider` in `core/ai/providers/` and register it in
`AppContainer`. Nothing else changes.

Your adapter must normalise the vendor's wire format into the shared
`StreamEvent` sequence, including synthesising a started/delta/completed
triple when the vendor sends tool calls whole rather than in fragments, the
way `GeminiProvider` does. It must terminate with exactly one `Completed` or
`Failed` and must never let a raw exception escape: the agent loop relies on
that to keep a session alive across a transport failure.

Test it against a recorded byte stream with `FakeTransport`, at a small
`chunkSize`, so the split-token cases are actually covered. See
`ProviderAdapterTest`.

## Adding a language

Copy `app/src/main/res/values/strings.xml` into
`app/src/main/res/values-<tag>/`, translate it, and add the tag to
`resourceConfigurations` in `app/build.gradle.kts`. No code changes. Layouts
use `start`/`end` rather than `left`/`right` throughout, so a right-to-left
language needs nothing extra; `values-ar` is there partly as the standing
check that this is still true.

## Running the tests

```bash
./gradlew :core:test              # fast, no device or Android SDK needed
./gradlew :app:testDebugUnitTest
./gradlew test                    # everything that runs on the JVM
```

Instrumented tests need hardware, and the 32-bit build is the higher-risk one,
so please run it before changing anything in `runtime-linux`:

```bash
./gradlew :runtime-linux:connectedDebugAndroidTest
```

## Style

Match the file you are editing. Beyond that: comments explain why, not what.
A comment that restates the line below it is noise; a comment that records the
constraint behind an odd-looking decision is the reason the next person does
not undo it.

## Reporting a bug

Settings shows the detection notes verbatim: the ABI, the RAM reading, the
Linux strategy, and every reason the tier was reduced. Paste that into the
issue. On unusual hardware it is usually the whole diagnosis.
