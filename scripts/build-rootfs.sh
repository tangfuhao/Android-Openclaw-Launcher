#!/usr/bin/env bash
#
# Builds a minimal Debian aarch64 rootfs with Node.js 22 and OpenClaw pre-installed.
# Designed to run on x86_64 Linux (GitHub Actions) using QEMU user-mode emulation.
#
# Prerequisites (Ubuntu):
#   sudo apt install debootstrap qemu-user-static binfmt-support xz-utils
#
# Usage:
#   sudo ./scripts/build-rootfs.sh
#
# Output:
#   rootfs-aarch64.tar.xz  (typically 150-250MB)

set -euo pipefail

ARCH="arm64"
SUITE="bookworm"
MIRROR="http://deb.debian.org/debian"
ROOTFS_DIR="$(mktemp -d)/rootfs"
OUTPUT="rootfs-aarch64.tar.xz"

echo "=== Stage 1: debootstrap (first stage, cross-arch) ==="
debootstrap \
    --arch="${ARCH}" \
    --foreign \
    --variant=minbase \
    --include=apt,bash,coreutils,findutils,grep,sed,gawk,tar,gzip,xz-utils,ca-certificates,curl,wget,git,procps,net-tools,openssh-client \
    "${SUITE}" \
    "${ROOTFS_DIR}" \
    "${MIRROR}"

echo "=== Stage 2: debootstrap (second stage, via QEMU) ==="
cp /usr/bin/qemu-aarch64-static "${ROOTFS_DIR}/usr/bin/"
chroot "${ROOTFS_DIR}" /usr/bin/qemu-aarch64-static /bin/bash -c \
    '/debootstrap/debootstrap --second-stage'

echo "=== Stage 3: Install Node.js + OpenClaw ==="
chroot "${ROOTFS_DIR}" /usr/bin/qemu-aarch64-static /bin/bash -c '
set -euo pipefail

cat > /etc/apt/sources.list << SOURCES
deb http://deb.debian.org/debian bookworm main contrib
deb http://deb.debian.org/debian bookworm-updates main contrib
deb http://security.debian.org/debian-security bookworm-security main contrib
SOURCES

apt-get update

# Node.js 22 LTS via NodeSource
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y nodejs

node --version
npm --version

# OpenClaw AI assistant
npm install -g openclaw@latest

# Commonly needed tools for AI agent workflows
apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    make \
    less \
    vim-tiny \
    jq

# DNS
cat > /etc/resolv.conf << DNS
nameserver 8.8.8.8
nameserver 8.8.4.4
DNS

# Shrink image
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
       /tmp/* /var/tmp/* \
       /usr/share/doc/* /usr/share/man/* /usr/share/info/* /usr/share/locale/*
'

echo "=== Stage 4: Cleanup ==="
rm -f "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"

echo "=== Stage 5: Compress ==="
cd "$(dirname "${ROOTFS_DIR}")"
XZ_OPT="-T0 -6" tar cJf "${OLDPWD}/${OUTPUT}" -C "${ROOTFS_DIR}" .

ROOTFS_SIZE=$(du -sh "${ROOTFS_DIR}" | cut -f1)
OUTPUT_SIZE=$(du -sh "${OLDPWD}/${OUTPUT}" | cut -f1)

echo ""
echo "=== Build complete ==="
echo "  Rootfs size (uncompressed): ${ROOTFS_SIZE}"
echo "  Archive size (compressed):  ${OUTPUT_SIZE}"
echo "  Output: ${OUTPUT}"
