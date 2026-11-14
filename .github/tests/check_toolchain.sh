#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Exercise scripts/check_toolchain.sh on a throwaway tree: the real pin files are
# copied in, then one declaration at a time is mutated. Every mismatch must fail
# and name the offending file.

set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SUT="$REPO_ROOT/scripts/check_toolchain.sh"

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
OUT="$TMP/stdout.txt"
ERR="$TMP/stderr.txt"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    [[ ! -s "$OUT" ]] || printf -- '--- stdout ---\n%s\n' "$(<"$OUT")" >&2
    [[ ! -s "$ERR" ]] || printf -- '--- stderr ---\n%s\n' "$(<"$ERR")" >&2
    exit 1
}

run_sut() {
    if bash "$SUT" --root "$ROOT" >"$OUT" 2>"$ERR"; then
        STATUS=0
    else
        STATUS=$?
    fi
}

make_root() {
    ROOT="$(mktemp -d "$TMP/root.XXXXXX")"
    mkdir -p -- "$ROOT/scripts" "$ROOT/rust-client" "$ROOT/rust-server"
    cp -- "$REPO_ROOT/scripts/toolchain.env" "$ROOT/scripts/toolchain.env"
    cp -- "$REPO_ROOT/rust-client/rust-toolchain.toml" "$ROOT/rust-client/rust-toolchain.toml"
    cp -- "$REPO_ROOT/rust-server/rust-toolchain.toml" "$ROOT/rust-server/rust-toolchain.toml"
    cp -- "$REPO_ROOT/rust-client/Cargo.toml" "$ROOT/rust-client/Cargo.toml"
    cp -- "$REPO_ROOT/rust-server/Cargo.toml" "$ROOT/rust-server/Cargo.toml"
}

expect_success() {
    local label="$1"
    run_sut
    [[ "$STATUS" -eq 0 ]] || fail "$label: expected exit 0, got $STATUS"
    grep -qF 'Toolchain pins consistent' "$OUT" || fail "$label: missing success line"
    printf '[OK] %s\n' "$label"
}

expect_mismatch() {
    local label="$1" file="$2" key="$3" replacement="$4"
    make_root
    sed -i "s/^\([[:space:]]*${key}[[:space:]]*=[[:space:]]*\"\)[^\"]*\"/\1${replacement}\"/" "$ROOT/$file"
    run_sut
    [[ "$STATUS" -eq 1 ]] || fail "$label: expected exit 1, got $STATUS"
    grep -qF "FAIL: $file:" "$ERR" || fail "$label: stderr does not name $file"
    printf '[OK] %s (exit %s): %s\n' "$label" "$STATUS" "$(<"$ERR")"
}

test_clean_tree_passes() {
    make_root
    expect_success "clean tree passes"
    # Vendored manifests are inert and must never be inspected.
    mkdir -p -- "$ROOT/rust-client/vendor/example"
    cat >"$ROOT/rust-client/vendor/example/Cargo.toml" <<'EOF'
[package]
name = "example"
version = "0.0.0"
rust-version = "0.0.1"
EOF
    expect_success "vendored Cargo.toml is ignored"
}

test_each_mismatch_fails() {
    expect_mismatch "client rust-toolchain channel mismatch" \
        "rust-client/rust-toolchain.toml" channel "1.98.0"
    expect_mismatch "server rust-toolchain channel mismatch" \
        "rust-server/rust-toolchain.toml" channel "1.98.0"
    expect_mismatch "client Cargo.toml rust-version mismatch" \
        "rust-client/Cargo.toml" rust-version "1.98.0"
    expect_mismatch "server Cargo.toml rust-version mismatch" \
        "rust-server/Cargo.toml" rust-version "1.98.0"
}

test_missing_pin_file_fails() {
    make_root
    rm -- "$ROOT/scripts/toolchain.env"
    run_sut
    [[ "$STATUS" -eq 1 ]] || fail "missing toolchain.env: expected exit 1, got $STATUS"
    grep -qF 'FAIL: scripts/toolchain.env:' "$ERR" || fail "missing toolchain.env is not named"
    printf '[OK] missing toolchain.env fails closed\n'
}

test_missing_declaration_fails() {
    make_root
    printf '[toolchain]\nprofile = "minimal"\n' >"$ROOT/rust-server/rust-toolchain.toml"
    run_sut
    [[ "$STATUS" -eq 1 ]] || fail "missing channel declaration: expected exit 1, got $STATUS"
    grep -qF 'FAIL: rust-server/rust-toolchain.toml:' "$ERR" ||
        fail "missing channel declaration does not name the file"
    printf '[OK] missing channel declaration fails closed\n'
}

failed=0
for test_case in test_clean_tree_passes test_each_mismatch_fails \
    test_missing_pin_file_fails test_missing_declaration_fails; do
    set +e
    (trap - EXIT; set -e; "$test_case")
    status=$?
    set -e
    if [[ "$status" -eq 0 ]]; then
        printf '[PASS] %s\n' "$test_case"
    else
        printf '[FAIL] %s (exit %s)\n' "$test_case" "$status" >&2
        failed=$((failed + 1))
    fi
done
[[ "$failed" -eq 0 ]] || exit 1
printf 'check_toolchain: 4 cases OK\n'
