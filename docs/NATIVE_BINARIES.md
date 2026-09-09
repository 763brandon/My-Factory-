# Native binaries and the Linux runtime

This repository builds one native library from source and deliberately ships
neither of the two others. This document explains why, and how to supply them.

## What is built here

`libforgepty.so` is compiled from `runtime-linux/src/main/cpp/forge_pty.cpp`
for armeabi-v7a, arm64-v8a, x86 and x86_64 on every build. It is a small JNI
shim around `forkpty(3)` and has no dependencies beyond bionic.

It exists because a pipe cannot carry a window size, cannot do job control,
and makes every ncurses program render as garbage. About two hundred lines of
C++ buys a terminal that behaves like a terminal.

## What is not shipped, and why

**PRoot** and **BusyBox** are not in this repository.

- They are separately licensed projects with their own build requirements.
  Vendoring prebuilt binaries into a source repository is how supply chains
  rot: nobody can tell what the committed `.so` was built from.
- A rootfs is executable code that will run on the user's device. The
  bootstrap therefore refuses to download an image it cannot verify, and the
  catalog in `RootfsCatalog.kt` ships placeholder checksums rather than
  checksums for files this project does not host.

The app handles their absence as a first-class state rather than an error. See
the degradation table in the README.

## Why `lib*.so` names

Since API 29 an app may not execute a file it wrote into its own data
directory. The native library directory is the one place the installer puts
files that stay executable, and the packager only puts files there if they are
named `lib*.so`.

So PRoot ships as `libproot.so`, BusyBox as `libbusybox.so`, and PRoot's
loader as `libloader.so`. They are ordinary ELF executables, not shared
libraries, despite the name. `LinuxRuntime` resolves them out of
`applicationInfo.nativeLibraryDir` and checks `canExecute()` before promising
anything.

The packaging block in `runtime-linux/build.gradle.kts` marks them as
`keepDebugSymbols` so the build does not strip an executable it mistakes for a
library.

## Building PRoot

PRoot needs to be cross-compiled against the NDK for each ABI. In outline:

```bash
export ANDROID_NDK=/path/to/ndk/27.2.12479018
export TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64

git clone https://github.com/proot-me/proot.git
cd proot/src

# Repeat for each ABI, with API 24 as the floor.
for ABI in armv7a-linux-androideabi24 aarch64-linux-android24 \
           i686-linux-android24 x86_64-linux-android24; do
  make clean
  make CC="$TOOLCHAIN/bin/clang --target=$ABI" \
       LD="$TOOLCHAIN/bin/clang --target=$ABI" \
       CFLAGS="-O2 -static" \
       proot
  # Place the result as libproot.so under the matching jniLibs directory.
done
```

Copy each result to:

```
runtime-linux/src/main/jniLibs/<abi>/libproot.so
runtime-linux/src/main/jniLibs/<abi>/libloader.so
runtime-linux/src/main/jniLibs/<abi>/libbusybox.so
```

Create that directory tree; it is not in the repository because it would be
empty.

PRoot's own build is the authoritative reference and changes between releases.
Treat the above as a starting point, not a recipe.

## Building BusyBox

BusyBox cross-compiles cleanly against the NDK with a static configuration.
The applets Forge actually needs are the file and text ones: `sh`, `ls`, `cat`,
`cp`, `mv`, `rm`, `mkdir`, `find`, `grep`, `sed`, `tar`, `wget`. A minimal
config keeps the binary near 1 MB per ABI.

## Publishing a rootfs

Once you host verified images, regenerate the catalog:

```bash
tools/refresh-rootfs-catalog.sh https://your-mirror.example/forge
```

It downloads each image, computes its SHA-256, and rewrites the `url` and
`sha256` fields in `RootfsCatalog.kt`. `RootfsCatalog.isProvisioned` then
returns true and the bootstrap screen becomes usable.

Do not shortcut this by disabling the checksum check. The check is the only
thing standing between a compromised mirror and arbitrary code execution on a
user's phone, and `RootfsBootstrapperTest` will tell you if it stops working.

## Testing the 32-bit path

armeabi-v7a is the higher-risk build and the reason this project exists. Test
it explicitly on a 32-bit AVD or device:

```bash
./gradlew :runtime-linux:connectedDebugAndroidTest
```

`NativePtyInstrumentedTest.theLibraryMatchesTheProcessWordSize` fails loudly if
a 64-bit library was loaded into a 32-bit process or the reverse, which is the
failure mode that otherwise shows up as an unexplained crash much later.
