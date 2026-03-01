#!/usr/bin/env bash
#
# Downloads the latest Termux proot binary and its libtalloc dependency
# for aarch64, placing them in jniLibs so they get packaged into the APK.
#
# The binaries are renamed to lib*.so because Android only extracts
# files matching lib*.so from the native library directory.
#
# Usage:
#   ./scripts/fetch-proot.sh

set -euo pipefail

TARGET_DIR="app/src/main/jniLibs/arm64-v8a"
PROOT_FILE="${TARGET_DIR}/libproot.so"
PROOT_LOADER_FILE="${TARGET_DIR}/libproot_loader.so"
TALLOC_FILE="${TARGET_DIR}/libtalloc.so"

PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb"
TALLOC_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"

mkdir -p "${TARGET_DIR}"

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

extract_deb_data() {
    local deb_file="$1"
    local out_dir="$2"
    python3 -c "
import sys, os
with open('$deb_file', 'rb') as f:
    f.read(8)  # ar magic
    while True:
        header = f.read(60)
        if len(header) < 60: break
        name = header[:16].strip().decode().rstrip('/')
        size = int(header[48:58].strip())
        data = f.read(size)
        if size % 2: f.read(1)
        if name == 'data.tar.xz':
            with open('$out_dir/data.tar.xz', 'wb') as out:
                out.write(data)
            break
"
    (cd "$out_dir" && xz -d data.tar.xz && tar xf data.tar)
}

echo "Downloading Termux proot package..."
curl -fsSL -o "${WORK_DIR}/proot.deb" "${PROOT_DEB_URL}"
mkdir -p "${WORK_DIR}/proot"
extract_deb_data "${WORK_DIR}/proot.deb" "${WORK_DIR}/proot"
cp "${WORK_DIR}/proot/data/data/com.termux/files/usr/bin/proot" "${PROOT_FILE}"
chmod +x "${PROOT_FILE}"
echo "proot binary saved to ${PROOT_FILE}"
file "${PROOT_FILE}"

cp "${WORK_DIR}/proot/data/data/com.termux/files/usr/libexec/proot/loader" "${PROOT_LOADER_FILE}"
chmod +x "${PROOT_LOADER_FILE}"
echo "proot loader saved to ${PROOT_LOADER_FILE}"
file "${PROOT_LOADER_FILE}"

echo ""
echo "Downloading Termux libtalloc package..."
curl -fsSL -o "${WORK_DIR}/talloc.deb" "${TALLOC_DEB_URL}"
mkdir -p "${WORK_DIR}/talloc"
extract_deb_data "${WORK_DIR}/talloc.deb" "${WORK_DIR}/talloc"
cp "${WORK_DIR}/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2."* "${TALLOC_FILE}"
echo "libtalloc saved to ${TALLOC_FILE}"
file "${TALLOC_FILE}"

echo ""
echo "Done. Both proot and libtalloc are ready in ${TARGET_DIR}/"
