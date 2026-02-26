#!/usr/bin/env bash
#
# Builds a minimal Debian aarch64 rootfs with Node.js and OpenClaw pre-installed.
# Intended to run on a CI server (x86_64 Linux with qemu-user-static for cross-arch).
#
# Prerequisites:
#   sudo apt install debootstrap qemu-user-static binfmt-support xz-utils
#
# Usage:
#   sudo ./scripts/build-rootfs.sh
#
# Output:
#   rootfs-aarch64.tar.xz  (~200MB compressed)

set -euo pipefail

ARCH="arm64"
SUITE="bookworm"
MIRROR="http://deb.debian.org/debian"
ROOTFS_DIR="$(mktemp -d)/rootfs"
OUTPUT="rootfs-aarch64.tar.xz"

echo "=== Stage 1: debootstrap ==="
debootstrap \
    --arch="${ARCH}" \
    --variant=minbase \
    --include=apt,bash,coreutils,findutils,grep,sed,gawk,tar,gzip,xz-utils,ca-certificates,curl,wget,git,procps,net-tools,openssh-client \
    "${SUITE}" \
    "${ROOTFS_DIR}" \
    "${MIRROR}"

echo "=== Stage 2: Configure inside chroot ==="
# Copy qemu static binary for cross-arch execution
cp /usr/bin/qemu-aarch64-static "${ROOTFS_DIR}/usr/bin/"

chroot "${ROOTFS_DIR}" /usr/bin/qemu-aarch64-static /bin/bash -c '
set -euo pipefail

# Configure apt sources with https
cat > /etc/apt/sources.list << SOURCES
deb http://deb.debian.org/debian bookworm main contrib
deb http://deb.debian.org/debian bookworm-updates main contrib
deb http://security.debian.org/debian-security bookworm-security main contrib
SOURCES

apt-get update

# Install Node.js LTS via NodeSource
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y nodejs

# Verify node + npm
node --version
npm --version

# Install OpenClaw globally
npm install -g openclaw

# Install commonly useful tools for the AI agent
apt-get install -y \
    python3 \
    python3-pip \
    make \
    less \
    vim-tiny \
    jq

# DNS resolver config (will be overwritten by the app, but good default)
cat > /etc/resolv.conf << DNS
nameserver 8.8.8.8
nameserver 8.8.4.4
DNS

# Clean up to reduce image size
apt-get clean
rm -rf /var/lib/apt/lists/*
rm -rf /var/cache/apt/archives/*
rm -rf /tmp/*
rm -rf /var/tmp/*
rm -rf /usr/share/doc/*
rm -rf /usr/share/man/*
rm -rf /usr/share/info/*
rm -rf /usr/share/locale/*
'

echo "=== Stage 3: Cleanup ==="
# Remove qemu static binary (not needed at runtime; proot handles execution)
rm -f "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"

echo "=== Stage 4: Compress ==="
cd "$(dirname "${ROOTFS_DIR}")"
tar cJf "${OLDPWD}/${OUTPUT}" -C "${ROOTFS_DIR}" .

ROOTFS_SIZE=$(du -sh "${ROOTFS_DIR}" | cut -f1)
OUTPUT_SIZE=$(du -sh "${OLDPWD}/${OUTPUT}" | cut -f1)

echo ""
echo "=== Build complete ==="
echo "  Rootfs size (uncompressed): ${ROOTFS_SIZE}"
echo "  Archive size (compressed):  ${OUTPUT_SIZE}"
echo "  Output: ${OUTPUT}"
echo ""
echo "Upload this file to GitHub Releases and update ROOTFS_URL in build.gradle.kts"
