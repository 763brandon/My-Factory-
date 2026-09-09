#!/usr/bin/env bash
#
# Regenerates RootfsCatalog.kt against a mirror you control.
#
# The catalog ships placeholder checksums because this repository does not host
# the rootfs images, and a rootfs is executable code that will run on a user's
# device. Publishing a checksum for a file we do not control would be a promise
# we cannot keep, so the bootstrap refuses to download until this has been run.
#
# Usage:
#   tools/refresh-rootfs-catalog.sh https://your-mirror.example/forge
#
# The mirror is expected to serve, by these exact names:
#   ubuntu-22.04-arm64.tar.gz
#   ubuntu-22.04-armhf.tar.gz
#   alpine-3.20-arm64.tar.gz
#   alpine-3.20-armhf.tar.gz
#   alpine-3.20-x86_64.tar.gz

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <mirror-base-url>" >&2
    exit 2
fi

MIRROR="${1%/}"
CATALOG="runtime-linux/src/main/java/com/myfactory/forge/runtime/proot/RootfsCatalog.kt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ ! -f "$CATALOG" ]; then
    echo "error: run this from the repository root; $CATALOG not found" >&2
    exit 1
fi

IMAGES=(
    "ubuntu-22.04-arm64"
    "ubuntu-22.04-armhf"
    "alpine-3.20-arm64"
    "alpine-3.20-armhf"
    "alpine-3.20-x86_64"
)

echo "Mirror: $MIRROR"
echo

for image in "${IMAGES[@]}"; do
    url="$MIRROR/$image.tar.gz"
    archive="$WORK/$image.tar.gz"

    printf '%-26s ' "$image"

    if ! curl -fsSL --retry 3 -o "$archive" "$url"; then
        echo "SKIPPED (not reachable at $url)"
        continue
    fi

    sha="$(sha256sum "$archive" | cut -d' ' -f1)"
    bytes="$(stat -c%s "$archive")"

    # Replace this image's url and sha256 in place, matching only inside its
    # own RootfsImage block so the five entries cannot cross-contaminate.
    python3 - "$CATALOG" "$image" "$url" "$sha" <<'PY'
import re, sys

catalog_path, image_id, url, sha = sys.argv[1:5]
source = open(catalog_path, encoding="utf-8").read()

# Find the block for this id and rewrite only its url and sha256 fields.
pattern = re.compile(
    r'(id = "' + re.escape(image_id) + r'".*?sha256 = ")[0-9a-fA-F]{64}(")',
    re.DOTALL,
)
if not pattern.search(source):
    sys.exit(f"could not locate the block for {image_id}")

def replace(match):
    block = match.group(1)
    block = re.sub(r'url = "[^"]*"', 'url = "' + url + '"', block)
    return block + sha + match.group(2)

open(catalog_path, "w", encoding="utf-8").write(pattern.sub(replace, source))
PY

    echo "${sha:0:16}...  ($((bytes / 1024 / 1024)) MB)"
done

echo
echo "Catalog updated. Verify with:"
echo "  git diff $CATALOG"
echo "  ./gradlew :runtime-linux:assembleDebug"
