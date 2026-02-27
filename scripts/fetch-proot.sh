#!/usr/bin/env bash
#
# Downloads a pre-compiled static proot binary for aarch64 and places it
# in the correct jniLibs location so it gets packaged into the APK.
#
# The binary is renamed to libproot.so because Android only extracts
# files matching lib*.so from the native library directory. This ensures
# proot is always executable (native lib dir is exempt from W^X restrictions).
#
# Usage:
#   ./scripts/fetch-proot.sh
#
# The script tries multiple sources in order of preference.

set -euo pipefail

TARGET_DIR="app/src/main/jniLibs/arm64-v8a"
TARGET_FILE="${TARGET_DIR}/libproot.so"

mkdir -p "${TARGET_DIR}"

# Source: skirsten/proot-portable-android-binaries (Termux-based, CI-built, statically linked)
PROOT_URL="https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot"

echo "Downloading proot static binary..."
if curl -fsSL -o "${TARGET_FILE}" "${PROOT_URL}"; then
    chmod +x "${TARGET_FILE}"
    echo "proot binary saved to ${TARGET_FILE}"
    file "${TARGET_FILE}"
    echo "Done."
    exit 0
fi

echo ""
echo "ERROR: Failed to download proot binary."
echo ""
echo "To build proot from source:"
echo "  1. Clone https://github.com/proot-me/proot"
echo "  2. Cross-compile for aarch64 with static linking:"
echo "     make -C src loader.elf loader-m32.elf proot"
echo "  3. Copy the static binary to ${TARGET_FILE}"
echo ""
echo "Or download a pre-built static binary from a trusted source"
echo "and place it at ${TARGET_FILE}"
exit 1
