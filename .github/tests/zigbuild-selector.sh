#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Integration check for the invocation contract the server scripts rely on:
# cargo-zigbuild exports the musl C/C++ toolchain (CC_* and friends) to build
# scripts only when the toolchain selector arrives via RUSTUP_TOOLCHAIN. The
# `+toolchain` argument form loses that environment, which is why
# scripts/zig_env.sh's callers never use it.
#
# Every other section test mocks the toolchain on purpose; this one is the only
# place that proves the real binary still behaves as the scripts assume. It runs
# after the toolchain steps in CI, so cargo-zigbuild, zig and the musl target
# are expected to be installed there.
set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/toolchain.env"
# The probe scrubs the same list the producers do, so an ambient CC_* cannot
# satisfy either half. zig-env.sh pins the list contents.
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/zig_env.sh"

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}
ok() { printf '[OK] %s\n' "$1"; }

for tool in cargo-zigbuild zig rustup; do
    command -v "$tool" >/dev/null 2>&1 || fail "'$tool' not found in PATH"
done
# Poison every variable the producers scrub: the probe itself scrubs them below,
# so a real file here can only be seen by build.rs if that scrubbing is lost.
# Without it, the `+toolchain` half would succeed and report a false "upstream
# changed".
export CC_x86_64_unknown_linux_musl=/bin/true
export CXX_x86_64_unknown_linux_musl=/bin/true
export AR_x86_64_unknown_linux_musl=/bin/true
export RANLIB_x86_64_unknown_linux_musl=/bin/true
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=/bin/true
rustup run "$RUST_TOOLCHAIN" rustc --version >/dev/null 2>&1 ||
    fail "rustup toolchain $RUST_TOOLCHAIN is missing (rustup toolchain install $RUST_TOOLCHAIN)"
rustup target list --installed --toolchain "$RUST_TOOLCHAIN" |
    grep -qx x86_64-unknown-linux-musl ||
    fail "musl target is missing (rustup target add x86_64-unknown-linux-musl --toolchain $RUST_TOOLCHAIN)"

mkdir -p "$TMP/probe/src"
cat >"$TMP/probe/Cargo.toml" <<'TOML'
[package]
name = "zigbuild-probe"
version = "0.1.0"
edition = "2021"
TOML
printf 'pub fn probe() -> bool {\n    true\n}\n' >"$TMP/probe/src/lib.rs"
# The build script is the observation point: cc-rs would consume these variables
# to compile C, so a missing CC_* is exactly the failure the scripts must avoid.
cat >"$TMP/probe/build.rs" <<'RS'
fn main() {
    let key = "CC_x86_64_unknown_linux_musl";
    let exported = std::env::var(key)
        .ok()
        .filter(|value| !value.is_empty())
        .filter(|value| std::path::Path::new(value).is_file());
    if exported.is_none() {
        panic!("{key} is not exported by cargo-zigbuild");
    }
    println!("cargo:rerun-if-changed=build.rs");
}
RS

build_probe() {
    local target_dir="$1"
    shift
    (
        cd "$TMP/probe" || exit 1
        # --offline: the probe has no dependencies, so the check never needs a
        # registry. CARGO_TARGET_DIR keeps the shared server cache untouched.
        env "${MUSL_ENV_UNSET[@]}" CARGO_TARGET_DIR="$target_dir" "$@" build \
            --target x86_64-unknown-linux-musl --offline
    )
}

if ! build_probe "$TMP/target-ok" env RUSTUP_TOOLCHAIN="$RUST_TOOLCHAIN" cargo-zigbuild \
    >"$TMP/ok.log" 2>&1; then
    printf -- '--- RUSTUP_TOOLCHAIN form ---\n%s\n' "$(<"$TMP/ok.log")" >&2
    fail 'RUSTUP_TOOLCHAIN no longer exports the musl toolchain to build scripts'
fi
ok "RUSTUP_TOOLCHAIN=<pin> cargo-zigbuild build экспортирует musl CC"

if build_probe "$TMP/target-plus" cargo-zigbuild "+$RUST_TOOLCHAIN" \
    >"$TMP/plus.log" 2>&1; then
    printf -- '--- +toolchain form ---\n%s\n' "$(<"$TMP/plus.log")" >&2
    fail 'cargo-zigbuild +<pin> produced a working musl CC: either upstream
changed, or the probe leaked CC_* (MUSL_ENV_UNSET scrubbing). Re-check the ban in
scripts/zig_env.sh and rust-server/build_linux*.sh before relying on that form'
fi
grep -qF 'CC_x86_64_unknown_linux_musl is not exported' "$TMP/plus.log" ||
    fail "unexpected failure mode for the +toolchain form:
$(tail -5 "$TMP/plus.log")"
ok "cargo-zigbuild +<pin> теряет musl CC — форма остаётся запрещённой"

printf 'zigbuild-selector: все проверки пройдены\n'
