#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

# Run the real provenance writer/verifier on copied source inputs and synthetic
# ELF data. No Cargo, toolchain, hashing, jq or verification logic is mocked.
set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$REPO_ROOT/.github/tests/fixtures/server-artifacts.sh"

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
OUT="$TMP/stdout.txt"
ERR="$TMP/stderr.txt"
WIRE_REVISION="CSQTT-WIRE-3"

# Hermetic toolchain probing: keep the real probe commands out of this suite so
# the manifest content is deterministic and the fake PATH is never consulted.
export CSQTT_PROVENANCE_RUSTC_VERSION="rustc 1.97.1"
export CSQTT_PROVENANCE_CARGO_ZIGBUILD_VERSION="0.24.0"
export CSQTT_PROVENANCE_ZIG_VERSION="0.17.0"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    [[ ! -s "$OUT" ]] || printf -- '--- stdout ---\n%s\n' "$(<"$OUT")" >&2
    [[ ! -s "$ERR" ]] || printf -- '--- stderr ---\n%s\n' "$(<"$ERR")" >&2
    exit 1
}

run_sut() {
    if bash "$SUT" "$@" >"$OUT" 2>"$ERR"; then
        STATUS=0
    else
        STATUS=$?
    fi
}

expect_success() {
    local label="$1"
    shift
    run_sut "$@"
    [[ "$STATUS" -eq 0 ]] || fail "$label: expected exit 0, got $STATUS"
}

expect_failure() {
    local label="$1" fragment="$2"
    shift 2
    run_sut "$@"
    [[ "$STATUS" -ne 0 ]] || fail "$label: command unexpectedly succeeded"
    grep -qF -- "$fragment" "$ERR" || fail "$label: stderr does not mention '$fragment'"
    printf '[OK] %s (exit %s): %s\n' "$label" "$STATUS" "$(<"$ERR")"
}

# Every negative calls this before its mutation. Explicit amd64 write/verify
# also passes on the pre-change implementation, so missing ARM cannot hide an
# integrity failure. Each root is new, with real, freshly hashed source inputs.
restore_valid_fixture() {
    ROOT="$(mktemp -d "$TMP/server-root.XXXXXX")"
    make_server_root "$ROOT" || fail "could not copy server source inputs"
    SUT="$ROOT/scripts/server_asset_provenance.sh"
    PATH_ARGS=(--root "$ROOT")
    if [[ "${1:-default}" == custom ]]; then
        ASSET_DIR="$ROOT/chosen assets"
        MANIFEST="$ROOT/chosen provenance/server.json"
        PATH_ARGS+=(--assets-dir "$ASSET_DIR" --manifest "$MANIFEST")
    else
        ASSET_DIR="$ROOT/app/build/generated/assets"
        MANIFEST="$ROOT/app/build/generated/provenance/csqtt-server.json"
    fi
    AMD64="$ASSET_DIR/recsqtt-linux-amd64"
    VALID_ARGS=("${PATH_ARGS[@]}" --assets=recsqtt-linux-amd64)
    write_server_elf "$AMD64" 2 62 "$WIRE_REVISION" || fail "could not write amd64 ELF fixture"
    expect_success "restored fixture write" write "${VALID_ARGS[@]}"
    expect_success "restored fixture verify" verify "${VALID_ARGS[@]}"
}

assert_amd64_manifest() {
    local manifest="$1"
    jq -e '.schema == "csqtt.server-asset-provenance.v5" and (.artifacts | length) == 1 and .artifacts[0].assetName == "recsqtt-linux-amd64" and .artifacts[0].target == "x86_64-unknown-linux-musl" and .artifacts[0].elfClass == 2 and .artifacts[0].elfMachine == 62' "$manifest" >/dev/null ||
        fail "manifest must describe exactly the amd64 production artifact"
    jq -e '.marker == "CSQTT_RUST_SERVER_PRODUCTION_V1" and .producer == "rust-server/build_linux.sh" and .runtime == "rust" and .features == []' "$manifest" >/dev/null ||
        fail "production identity and default features must be preserved"
    jq -e '(.toolchain | type) == "object" and .toolchain.rustc == "rustc 1.97.1" and .toolchain.cargoZigbuild == "0.24.0" and .toolchain.zig == "0.17.0" and .toolchain.source == "env-override"' "$manifest" >/dev/null ||
        fail "manifest must record the probed toolchain object and its resolution source"
}

rewrite_manifest() {
    jq "$@" "$MANIFEST" >"$MANIFEST.edit" || fail "could not mutate manifest fixture"
    mv -- "$MANIFEST.edit" "$MANIFEST"
}

test_default_amd64_manifest() {
    restore_valid_fixture
    printf 'unrelated asset\n' >"$ASSET_DIR/notes.txt"
    local unrelated_hash
    unrelated_hash="$(sha256sum "$ASSET_DIR/notes.txt")"
    # The copied script derives its root and generated paths without flags.
    expect_success "default amd64-only write" write
    assert_amd64_manifest "$MANIFEST"
    expect_success "default amd64-only verify" verify
    [[ "$(sha256sum "$ASSET_DIR/notes.txt")" == "$unrelated_hash" ]] ||
        fail "default write/verify changed an unrelated asset"

    restore_valid_fixture custom
    printf 'custom unrelated asset\n' >"$ASSET_DIR/csqtt-linux-mips"
    unrelated_hash="$(sha256sum "$ASSET_DIR/csqtt-linux-mips")"
    # Known ARM names outside the selected assets directory are irrelevant.
    write_server_elf "$ROOT/app/build/generated/assets/csqtt-linux-arm64" 2 183 "$WIRE_REVISION"
    expect_success "explicit amd64 and custom paths" write "${PATH_ARGS[@]}" --assets recsqtt-linux-amd64
    assert_amd64_manifest "$MANIFEST"
    expect_success "equals-form assets and custom paths" verify --root "$ROOT" \
        --assets=recsqtt-linux-amd64 --assets-dir="$ASSET_DIR" --manifest="$MANIFEST"
    [[ "$(sha256sum "$ASSET_DIR/csqtt-linux-mips")" == "$unrelated_hash" ]] ||
        fail "custom write/verify changed an unrelated asset"
}

test_invalid_asset_selection() {
    local selection form mode class machine
    local -a selection_args
    # The pre-rebrand amd64 name is now just another unsupported selection, and
    # it must stay rejected: accepting it would let an old asset slip past the
    # renamed provenance allowlist.
    for selection in csqtt-linux-amd64 csqtt-linux-arm64 csqtt-linux-armv7 csqtt-linux-mips '' ',' \
        recsqtt-linux-amd64,recsqtt-linux-amd64 recsqtt-linux-amd64,csqtt-linux-arm64 \
        recsqtt-linux-amd64,csqtt-linux-arm64,csqtt-linux-armv7 \
        ,recsqtt-linux-amd64 recsqtt-linux-amd64, recsqtt-linux-amd64,, \
        $'recsqtt-linux-amd64\ncsqtt-linux-arm64'; do
        for form in split equals; do
            for mode in write verify; do
                restore_valid_fixture
                case "$selection" in
                    csqtt-linux-arm64 | csqtt-linux-armv7)
                        if [[ "$selection" == csqtt-linux-arm64 ]]; then
                            class=2 machine=183
                        else
                            class=1 machine=40
                        fi
                        # Give ARM a complete matching payload/record, not a
                        # missing file that could masquerade as selection denial.
                        write_server_elf "$ASSET_DIR/$selection" "$class" "$machine" "$WIRE_REVISION"
                        rewrite_manifest --arg name "$selection" --argjson class "$class" \
                            --argjson machine "$machine" \
                            --arg target "$([[ "$class" == 2 ]] && printf aarch64-unknown-linux-musl || printf armv7-unknown-linux-musleabihf)" \
                            --argjson size "$(stat -c '%s' "$ASSET_DIR/$selection")" \
                            --arg sha "$(sha256sum "$ASSET_DIR/$selection" | cut -d' ' -f1 | tr '[:lower:]' '[:upper:]')" \
                            '.artifacts = [{assetName: $name, target: $target, elfClass: $class, elfMachine: $machine, binarySize: $size, binarySha256: $sha}]'
                        ;;
                esac
                if [[ "$form" == split ]]; then
                    selection_args=(--assets "$selection")
                else
                    selection_args=("--assets=$selection")
                fi
                expect_failure "$mode rejects $form selection '$selection'" "--assets" \
                    "$mode" "${PATH_ARGS[@]}" "${selection_args[@]}"
            done
        done
    done
    for mode in write verify; do
        restore_valid_fixture
        expect_failure "$mode rejects missing --assets value" "--assets requires a value" \
            "$mode" "${PATH_ARGS[@]}" --assets
    done
}

test_legacy_manifest_rejected() {
    restore_valid_fixture
    local source_hash
    source_hash="$(jq -r '.buildInputSha256' "$MANIFEST")"
    # Append complete legacy records to a FRESH manifest; there are no stale ARM
    # files in ASSET_DIR, and its source/Cargo hashes remain valid.
    rewrite_manifest '.artifacts += [
        (.artifacts[0] | .assetName = "csqtt-linux-arm64" | .target = "aarch64-unknown-linux-musl" | .elfClass = 2 | .elfMachine = 183),
        (.artifacts[0] | .assetName = "csqtt-linux-armv7" | .target = "armv7-unknown-linux-musleabihf" | .elfClass = 1 | .elfMachine = 40)
    ]'
    [[ "$(jq -r '.buildInputSha256' "$MANIFEST")" == "$source_hash" ]] ||
        fail "legacy fixture mutation changed the fresh source hash"
    expect_failure "default verify rejects legacy three-record manifest" "artifact count mismatch" \
        verify "${PATH_ARGS[@]}"
}

test_stale_arm_assets_rejected() {
    local location asset content mode class machine stale_hash amd64_hash manifest_hash
    for location in default custom; do
        for asset in csqtt-linux-arm64 csqtt-linux-armv7; do
            for content in elf empty; do
                for mode in write verify; do
                    restore_valid_fixture "$location"
                    if [[ "$asset" == csqtt-linux-arm64 ]]; then
                        class=2 machine=183
                    else
                        class=1 machine=40
                    fi
                    write_server_elf "$ASSET_DIR/$asset" "$class" "$machine" "$WIRE_REVISION"
                    [[ "$content" != empty ]] || : >"$ASSET_DIR/$asset"
                    stale_hash="$(sha256sum "$ASSET_DIR/$asset")"
                    amd64_hash="$(sha256sum "$AMD64")"
                    manifest_hash="$(sha256sum "$MANIFEST")"
                    # Direct invocation has no producer: this gate must stand on
                    # its own even when Gradle's native producer is disabled.
                    expect_failure "$mode rejects $content stale $asset in $location assets" \
                        "stale ARM server asset" "$mode" "${VALID_ARGS[@]}"
                    [[ "$(sha256sum "$ASSET_DIR/$asset")" == "$stale_hash" ]] ||
                        fail "stale ARM check deleted or changed $asset"
                    [[ "$(sha256sum "$AMD64")" == "$amd64_hash" ]] ||
                        fail "stale ARM check changed amd64"
                    [[ "$(sha256sum "$MANIFEST")" == "$manifest_hash" ]] ||
                        fail "stale ARM check changed the manifest"
                done
            done
        done
    done
}

test_integrity_and_features_fail_closed() {
    local mode mutation fragment feature
    for mode in write verify; do
        for mutation in missing empty short non-elf magic class endianness machine machine-high-byte wire; do
            restore_valid_fixture
            case "$mutation" in
                missing)
                    rm -- "$AMD64"
                    fragment="Linux server asset recsqtt-linux-amd64 not found"
                    ;;
                empty)
                    : >"$AMD64"
                    fragment="Linux server asset recsqtt-linux-amd64 is empty"
                    ;;
                short)
                    printf '\177ELF\002\001' >"$AMD64"
                    fragment="Invalid ELF header"
                    ;;
                non-elf)
                    printf 'substituted non-ELF server payload\n' >"$AMD64"
                    fragment="is not ELF"
                    ;;
                magic)
                    printf 'BAD!' | dd of="$AMD64" bs=1 count=4 conv=notrunc status=none
                    fragment="is not ELF"
                    ;;
                class)
                    write_server_elf "$AMD64" 1 62 "$WIRE_REVISION"
                    fragment="ELF class or endianness mismatch"
                    ;;
                endianness)
                    printf '\002' | dd of="$AMD64" bs=1 seek=5 count=1 conv=notrunc status=none
                    fragment="ELF class or endianness mismatch"
                    ;;
                machine)
                    write_server_elf "$AMD64" 2 183 "$WIRE_REVISION"
                    fragment="ELF machine mismatch"
                    ;;
                machine-high-byte)
                    write_server_elf "$AMD64" 2 318 "$WIRE_REVISION"
                    fragment="expected 62, got 318"
                    ;;
                wire)
                    write_server_elf "$AMD64" 2 62 CSQTT-WIRE-X
                    fragment="does not expose wire protocol revision $WIRE_REVISION"
                    ;;
            esac
            expect_failure "$mode rejects $mutation amd64 payload" "$fragment" "$mode" "${VALID_ARGS[@]}"
        done
    done

    for mutation in payload-size payload-sha size sha target elf-class elf-machine source-hash source cargo-toml cargo-lock; do
        restore_valid_fixture
        case "$mutation" in
            payload-size)
                printf 'extra payload bytes\n' >>"$AMD64"
                fragment="provenance size mismatch"
                ;;
            payload-sha)
                printf 'X' | dd of="$AMD64" bs=1 seek=32 count=1 conv=notrunc status=none
                fragment="asset does not match provenance"
                ;;
            size)
                rewrite_manifest '.artifacts[0].binarySize += 1'
                fragment="provenance size mismatch"
                ;;
            sha)
                rewrite_manifest '.artifacts[0].binarySha256 = "0000000000000000000000000000000000000000000000000000000000000000"'
                fragment="asset does not match provenance"
                ;;
            target)
                rewrite_manifest '.artifacts[0].target = "aarch64-unknown-linux-musl"'
                fragment="provenance target mismatch"
                ;;
            elf-class)
                rewrite_manifest '.artifacts[0].elfClass = 1'
                fragment="provenance ELF class mismatch"
                ;;
            elf-machine)
                rewrite_manifest '.artifacts[0].elfMachine = 183'
                fragment="provenance ELF machine mismatch"
                ;;
            source-hash)
                rewrite_manifest '.buildInputSha256 = "0000000000000000000000000000000000000000000000000000000000000000"'
                fragment="source changed after the production server build"
                ;;
            source)
                printf '\n// provenance fixture mutation\n' >>"$ROOT/rust-server/main.rs"
                fragment="source changed after the production server build"
                ;;
            cargo-toml)
                printf '\n# provenance fixture mutation\n' >>"$ROOT/rust-server/Cargo.toml"
                fragment="Cargo.toml changed after the production server build"
                ;;
            cargo-lock)
                printf '\n# provenance fixture mutation\n' >>"$ROOT/rust-server/Cargo.lock"
                fragment="Cargo.lock changed after the production server build"
                ;;
        esac
        expect_failure "verify rejects $mutation integrity mutation" "$fragment" verify "${VALID_ARGS[@]}"
    done

    for feature in diagnostics fixture-only; do
        restore_valid_fixture
        expect_success "record $feature with --features" write "${VALID_ARGS[@]}" --features "$feature"
        expect_failure "verify rejects unapproved $feature" "'$feature' cargo feature" verify "${VALID_ARGS[@]}"
        if [[ "$feature" == diagnostics ]]; then
            expect_success "allow diagnostics with equals form" verify "${VALID_ARGS[@]}" --allow-feature=diagnostics
            expect_success "allow diagnostics with split form" verify "${VALID_ARGS[@]}" --allow-feature diagnostics
        fi
    done

    restore_valid_fixture
    expect_success "record two features with equals form" write "${VALID_ARGS[@]}" --features=diagnostics,fixture-only
    expect_failure "allowing diagnostics does not authorize another recorded feature" "'fixture-only' cargo feature" \
        verify "${VALID_ARGS[@]}" --allow-feature=diagnostics
}

test_toolchain_recorded_and_compared() {
    restore_valid_fixture
    assert_amd64_manifest "$MANIFEST"

    # Informational only: a mismatch on a re-verify warns but stays exit 0.
    rewrite_manifest '.toolchain.rustc = "0.0.0"'
    expect_success "toolchain mismatch warns by default" verify "${VALID_ARGS[@]}"
    grep -qF 'toolchain mismatch for rustc' "$ERR" ||
        fail "default verify did not warn about the toolchain mismatch"

    # The explicit flag turns the same drift into a hard failure against the pin.
    run_sut verify "${VALID_ARGS[@]}" --strict-toolchain
    [[ "$STATUS" -eq 1 ]] || fail "--strict-toolchain must fail on a toolchain mismatch, got exit $STATUS"
    grep -qF 'strict toolchain drift for rustc' "$ERR" ||
        fail "--strict-toolchain did not report the pin drift"
    grep -qF 'pin 1.97.1' "$ERR" ||
        fail "--strict-toolchain drift message does not name the pin"

    # The environment variable is the CI-friendly equivalent of the flag.
    if CSQTT_STRICT_TOOLCHAIN=1 bash "$SUT" verify "${VALID_ARGS[@]}" >"$OUT" 2>"$ERR"; then
        STATUS=0
    else
        STATUS=$?
    fi
    [[ "$STATUS" -eq 1 ]] || fail "CSQTT_STRICT_TOOLCHAIN=1 must fail on a toolchain mismatch, got exit $STATUS"
    grep -qF 'strict toolchain drift for rustc' "$ERR" ||
        fail "CSQTT_STRICT_TOOLCHAIN=1 did not report the pin drift"

    restore_valid_fixture
    expect_success "matching toolchain passes strict verification" verify "${VALID_ARGS[@]}" --strict-toolchain
    if grep -qF 'warning:' "$ERR"; then
        fail "strict verification of matching pins must not emit warnings"
    fi

    # Removing the informational object is still an identity failure: the schema
    # promises it, so a producer that omits it cannot pass.
    rewrite_manifest 'del(.toolchain)'
    expect_failure "verify rejects a manifest without toolchain info" "identity mismatch" \
        verify "${VALID_ARGS[@]}"
}

test_strict_toolchain_pins() {
    local field pin_var replacement
    # Strict compares the manifest to the pins, not just to this host: a pin
    # changed in the temp tree must fail even though recorded == current here.
    for field in rustc cargoZigbuild zig; do
        case "$field" in
            rustc)
                pin_var=RUST_TOOLCHAIN
                replacement=1.98.0
                ;;
            cargoZigbuild)
                pin_var=CARGO_ZIGBUILD_VERSION
                replacement=0.99.0
                ;;
            zig)
                pin_var=ZIG_VERSION
                replacement=0.99.0
                ;;
        esac
        restore_valid_fixture
        sed -i "s/^${pin_var}=.*/${pin_var}=\"${replacement}\"/" "$ROOT/scripts/toolchain.env"
        grep -qF "${pin_var}=\"${replacement}\"" "$ROOT/scripts/toolchain.env" ||
            fail "could not mutate $pin_var in the temp pin file"
        run_sut verify "${VALID_ARGS[@]}" --strict-toolchain
        [[ "$STATUS" -eq 1 ]] || fail "strict verify must fail when $pin_var drifts, got exit $STATUS"
        grep -qF "strict toolchain drift for $field" "$ERR" ||
            fail "strict drift failure does not name $field"
        grep -qF "pin $replacement" "$ERR" ||
            fail "strict drift failure does not name the $pin_var pin"
        printf '[OK] strict mode rejects %s pin drift\n' "$pin_var"
    done

    # No pin file means no contract: strict must say so instead of falling back
    # to this host's tools.
    restore_valid_fixture
    rm -- "$ROOT/scripts/toolchain.env"
    run_sut verify "${VALID_ARGS[@]}" --strict-toolchain
    [[ "$STATUS" -eq 1 ]] || fail "strict verify must fail without scripts/toolchain.env, got exit $STATUS"
    grep -qF 'requires scripts/toolchain.env' "$ERR" ||
        fail "missing pin file failure does not name scripts/toolchain.env"
    printf '[OK] strict mode fails closed without scripts/toolchain.env\n'
}

# Keep running the named cases during red so default/selection failures are
# visible alongside the unchanged fail-closed integrity behavior.
failed=0
for test_case in test_default_amd64_manifest test_invalid_asset_selection \
    test_legacy_manifest_rejected test_stale_arm_assets_rejected \
    test_integrity_and_features_fail_closed test_toolchain_recorded_and_compared \
    test_strict_toolchain_pins; do
    set +e
    ( trap - EXIT; set -e; "$test_case" )
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
printf 'server-asset-provenance: 7 cases OK\n'
