#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Tiered gate around rust-client/abis.txt, the single source of truth for the
# client ABI list.
#
# Tier 1 (must derive from the manifest): app/build.gradle.kts,
# scripts/device_abi.sh, rust-client/build_so.sh,
# rust-client/build_android_abis.sh and scripts/native_client_provenance.sh.
# Each must reference the manifest literally, and every ABI in the manifest must
# appear in every per-ABI map of those files. Adding an ABI to the manifest
# therefore fails this gate until all maps know it.
#
# Tier 2 (must match the manifest-derived packaging sets): build_apk.sh
# (loop and copy_apk), collect-release-assets.sh, release-note.sh and the
# `abis:` workflow inputs. Tier 2 may add `universal`, never another ABI, and
# the release assets intentionally omit TIER2_EXCLUDED (x86_64).
#
# Tier 3 (ignored): the Android runtime Build.SUPPORTED_ABIS and documentation.
#
# Usage: scripts/check_abi_list.sh [--root DIR]
# Exit: 0 when consistent, 1 on the first violation.

set -euo pipefail
export LC_ALL=C

usage() {
    printf 'Usage: %s [--root DIR]\n' "$(basename "$0")" >&2
}

fail() {
    printf 'abi-list: FAIL: %s\n' "$*" >&2
    exit 1
}

ROOT=""
while (($#)); do
    case "$1" in
        --root)
            (($# >= 2)) || {
                usage
                exit 2
            }
            ROOT="$2"
            shift
            ;;
        --root=*) ROOT="${1#--root=}" ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
    shift
done

if [[ -z "$ROOT" ]]; then
    ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
fi
[[ -d "$ROOT" ]] || fail "root directory not found: $ROOT"
ROOT="$(cd -- "$ROOT" && pwd)"

MANIFEST="$ROOT/rust-client/abis.txt"
PASS=0
ok() { PASS=$((PASS + 1)); }

# --- Manifest -----------------------------------------------------------------
[[ -f "$MANIFEST" ]] || fail "missing ABI manifest: rust-client/abis.txt"
[[ -s "$MANIFEST" ]] || fail "ABI manifest is empty: rust-client/abis.txt"
ok

ABIS=()
while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line//[[:space:]]/}"
    [[ -n "$line" ]] || continue
    ABIS+=("$line")
done <"$MANIFEST"
((${#ABIS[@]} > 0)) || fail "ABI manifest lists no ABIs: rust-client/abis.txt"
ok

declare -A seen=()
for abi in "${ABIS[@]}"; do
    # No hardcoded vocabulary here: the manifest is the source of truth. A typo
    # is caught below by the per-ABI map checks, which must know every entry.
    [[ "$abi" =~ ^[a-z0-9_][a-z0-9_-]*$ ]] ||
        fail "invalid ABI '$abi' in rust-client/abis.txt (expected a lowercase Android ABI name)"
    [[ -z "${seen[$abi]:-}" ]] || fail "duplicate ABI '$abi' in rust-client/abis.txt"
    seen[$abi]=1
done
ok

in_manifest() {
    local needle="$1" abi
    for abi in "${ABIS[@]}"; do
        [[ "$abi" == "$needle" ]] && return 0
    done
    return 1
}

# --- Tier 1: every consumer reads the manifest --------------------------------
TIER1_FILES=(
    app/build.gradle.kts
    scripts/device_abi.sh
    rust-client/build_so.sh
    rust-client/build_android_abis.sh
    scripts/native_client_provenance.sh
)
for rel in "${TIER1_FILES[@]}"; do
    [[ -f "$ROOT/$rel" ]] || fail "missing Tier-1 consumer: $rel"
    grep -qF 'rust-client/abis.txt' "$ROOT/$rel" ||
        fail "$rel does not reference rust-client/abis.txt; Tier-1 consumers must derive the ABI list from the manifest"
    ok
done

# The two build scripts mark their derived default so the origin is obvious in
# place (and so this gate can assert it).
for rel in rust-client/build_so.sh rust-client/build_android_abis.sh; do
    grep -qF '# ABI list: rust-client/abis.txt' "$ROOT/$rel" ||
        fail "$rel is missing the '# ABI list: rust-client/abis.txt' marker next to the default ABI list"
    ok
done

# The manifest must be a native build input: that is what makes Gradle rebuild
# and provenance re-hash when the ABI list changes.
grep -qF 'rust-client/abis.txt' "$ROOT/rust-client/native-inputs.txt" ||
    fail "rust-client/native-inputs.txt does not list rust-client/abis.txt"
ok

# --- Tier 1: every manifest ABI exists in every mapping -----------------------

# Strip Kotlin comments so a commented-out map entry cannot satisfy the gate.
strip_kotlin_comments() {
    awk '
        {
            line = $0
            out = ""
            while (length(line) > 0) {
                if (in_block) {
                    if (match(line, /\*\//)) {
                        line = substr(line, RSTART + 2)
                        in_block = 0
                    } else {
                        line = ""
                    }
                } else if (match(line, /\/\*/)) {
                    out = out substr(line, 1, RSTART - 1)
                    line = substr(line, RSTART + 2)
                    in_block = 1
                } else if (match(line, /\/\//)) {
                    out = out substr(line, 1, RSTART - 1)
                    line = ""
                } else {
                    out = out line
                    line = ""
                }
            }
            print out
        }
    '
}

GRADLE="$ROOT/app/build.gradle.kts"
# Scope to the real map: a "$abi" -> elsewhere in the file, or in a comment,
# must not stand in for the when (abi) branch.
gradle_map="$(awk '/^fun clientCargoArtifacts\(/ { inside = 1 } inside { print } inside && /^}/ { exit }' "$GRADLE")"
[[ -n "$gradle_map" ]] || fail "app/build.gradle.kts has no clientCargoArtifacts() ABI map"
gradle_map_code="$(strip_kotlin_comments <<<"$gradle_map")"
for abi in "${ABIS[@]}"; do
    grep -qF "\"$abi\" ->" <<<"$gradle_map_code" ||
        fail "app/build.gradle.kts ABI target/suffix when-map has no entry for '$abi' (add it together with rust-client/abis.txt)"
    ok
done

function_body() {
    local file="$1" fn="$2"
    awk -v fn="$fn" '
        $0 ~ "^[[:space:]]*" fn "\\(\\)[[:space:]]*\\{" { inside = 1 }
        inside { print }
        inside && /^}/ { exit }
    ' "$file"
}

assert_function_map() {
    local file="$1" fn="$2" body abi
    body="$(function_body "$file" "$fn")"
    [[ -n "$body" ]] || fail "$file has no $fn() mapping"
    for abi in "${ABIS[@]}"; do
        grep -qF "$abi" <<<"$body" ||
            fail "$file $fn() has no entry for '$abi' (add it together with rust-client/abis.txt)"
        ok
    done
}

assert_function_map "$ROOT/rust-client/build_android_abis.sh" target_for_abi
assert_function_map "$ROOT/rust-client/build_android_abis.sh" target_dir_for_abi

for fn in abi_target abi_elf_class abi_elf_machine abi_target_dir valid_abi; do
    assert_function_map "$ROOT/scripts/native_client_provenance.sh" "$fn"
done

# build_so.sh maps ABIs to cargo targets inside the install loop's case block.
BUILD_SO="$ROOT/rust-client/build_so.sh"
build_so_case="$(awk '/case "\$abi" in/{inside=1} inside{print} inside && /^[[:space:]]*esac/{exit}' "$BUILD_SO")"
[[ -n "$build_so_case" ]] || fail "rust-client/build_so.sh has no case \"\$abi\" map for install_lib"
grep -qF "install_lib \"\$abi\"" <<<"$build_so_case" ||
    fail "rust-client/build_so.sh install_lib map does not call install_lib \"\$abi\""
ok
for abi in "${ABIS[@]}"; do
    grep -qF "$abi)" <<<"$build_so_case" ||
        fail "rust-client/build_so.sh install_lib map has no entry for '$abi' (add it together with rust-client/abis.txt)"
    ok
done

# --- Tier 2: packaging sets are exact and order-insensitive -------------------
#
# Tier 2 may only add `universal`; it may not invent ABIs. The release bundle
# intentionally omits x86_64: `recsqtt-x86_64.apk` is not published as a
# separate asset and x86_64 ships only inside the universal APK. See BUILDING.md,
# "Что попадает в релиз".
TIER2_EXCLUDED=(x86_64)

sorted_tokens() {
    local raw="$1" token
    raw="${raw//,/ }"
    for token in $raw; do
        token="${token//\"/}"
        token="${token//\'/}"
        [[ -n "${token//[[:space:]]/}" ]] || continue
        printf '%s\n' "$token"
    done | LC_ALL=C sort -u
}

assert_known_tier2_tokens() {
    local file="$1" label="$2" tokens="$3" token
    tokens="${tokens//,/ }"
    for token in $tokens; do
        token="${token//\"/}"
        token="${token//\'/}"
        [[ -n "${token//[[:space:]]/}" ]] || continue
        if [[ "$token" == *'$'* ]]; then
            fail "$file $label must name ABIs literally, not via '$token'"
        fi
        [[ "$token" == "universal" ]] && continue
        in_manifest "$token" ||
            fail "$file $label names '$token', which is not in rust-client/abis.txt (Tier-2 may only add 'universal')"
        ok
    done
}

# Compares two ABI lists as sets: order-insensitive, unknown tokens still fail.
assert_set_equals() {
    local file="$1" label="$2" actual="$3" expected="$4"
    local actual_set expected_set missing extra
    assert_known_tier2_tokens "$file" "$label" "$actual"
    actual_set="$(sorted_tokens "$actual")"
    expected_set="$(sorted_tokens "$expected")"
    [[ -n "$actual_set" ]] || fail "$file $label is empty; expected ABI set [${expected_set//$'\n'/ }]"
    [[ -n "$expected_set" ]] || fail "$file $label has no expected ABI set (gate bug)"
    if [[ "$actual_set" != "$expected_set" ]]; then
        missing="$(comm -23 <(printf '%s\n' "$expected_set") <(printf '%s\n' "$actual_set"))"
        extra="$(comm -13 <(printf '%s\n' "$expected_set") <(printf '%s\n' "$actual_set"))"
        fail "$file $label ABI set mismatch: missing [${missing//$'\n'/ }], extra [${extra//$'\n'/ }] (required: [${expected_set//$'\n'/ }])"
    fi
    ok
}

assert_manifest_subset() {
    local file="$1" label="$2" tokens="$3" token found=0
    tokens="${tokens//,/ }"
    for token in $tokens; do
        token="${token//\"/}"
        token="${token//\'/}"
        [[ -n "${token//[[:space:]]/}" ]] || continue
        if [[ "$token" == *'$'* ]]; then
            fail "$file $label must name ABIs literally, not via '$token'"
        fi
        in_manifest "$token" ||
            fail "$file $label names '$token', which is not in rust-client/abis.txt (expected a non-empty subset)"
        found=1
        ok
    done
    ((found)) || fail "$file $label is empty; expected a non-empty subset of rust-client/abis.txt"
}

for excluded in "${TIER2_EXCLUDED[@]}"; do
    in_manifest "$excluded" ||
        fail "TIER2_EXCLUDED entry '$excluded' is not in rust-client/abis.txt"
    ok
done

PACKAGING_SET="${ABIS[*]} universal"
COLLECT_SET=""
for abi in "${ABIS[@]}"; do
    excluded=0
    for candidate in "${TIER2_EXCLUDED[@]}"; do
        [[ "$abi" == "$candidate" ]] && excluded=1
    done
    ((excluded)) || COLLECT_SET+=" $abi"
done
COLLECT_SET+=" universal"

BUILD_APK="$ROOT/scripts/build_apk.sh"
[[ -f "$BUILD_APK" ]] || fail "missing Tier-2 consumer: scripts/build_apk.sh"
apk_loop="$(
    sed -nE 's/^[[:space:]]*for[[:space:]]+abi[[:space:]]+in[[:space:]]+(.*)[[:space:]]*;[[:space:]]*do.*$/\1/p' "$BUILD_APK" |
        head -n 1
)"
[[ -n "$apk_loop" ]] || fail "could not parse the ABI loop in scripts/build_apk.sh"
assert_set_equals "scripts/build_apk.sh" "ABI loop" "$apk_loop" "$PACKAGING_SET"

copy_abis="$(
    sed -nE 's/^[[:space:]]*copy_apk[[:space:]]+([^[:space:]]+)[[:space:]]*$/\1/p' "$BUILD_APK"
)"
[[ -n "$copy_abis" ]] || fail "could not parse copy_apk calls in scripts/build_apk.sh"
assert_set_equals "scripts/build_apk.sh" "copy_apk calls" "$copy_abis" "$PACKAGING_SET"

COLLECT="$ROOT/.github/scripts/collect-release-assets.sh"
[[ -f "$COLLECT" ]] || fail "missing Tier-2 consumer: .github/scripts/collect-release-assets.sh"
collect_abis="$(
    sed -nE 's/^[[:space:]]*abis=\(([^)]*)\).*$/\1/p' "$COLLECT" |
        head -n 1
)"
[[ -n "$collect_abis" ]] || fail "could not parse abis=(...) in .github/scripts/collect-release-assets.sh"
assert_set_equals ".github/scripts/collect-release-assets.sh" "abis=(...)" "$collect_abis" "$COLLECT_SET"

RELEASE_NOTE="$ROOT/.github/scripts/release-note.sh"
[[ -f "$RELEASE_NOTE" ]] || fail "missing Tier-2 consumer: .github/scripts/release-note.sh"
note_abis="$(
    sed -nE 's/.*recsqtt-[$]version-([A-Za-z0-9_-]+)[.]apk.*/\1/p' "$RELEASE_NOTE"
)"
[[ -n "$note_abis" ]] || fail "could not parse recsqtt-\$version-<abi>.apk lines in .github/scripts/release-note.sh"
assert_set_equals ".github/scripts/release-note.sh" "release asset list" "$note_abis" "$COLLECT_SET"

# Workflow `abis:` inputs feed -Pcsqtt.abis. build.yml (workflow_call default)
# and release.yml must cover the manifest exactly; beta.yml may pass any
# non-empty subset. Both the inline form (`abis: arm64-v8a`) and the
# workflow_call form (`abis:` followed by `default: '...'`) are covered.
workflow_abi_values() {
    awk '
        /^[[:space:]]*#/ { next }
        /^[[:space:]]*abis:[[:space:]]*$/ { pending = 4; next }
        pending > 0 {
            if ($0 ~ /^[[:space:]]*default:/) {
                sub(/^[[:space:]]*default:[[:space:]]*/, "")
                print
                pending = 0
            } else {
                pending--
            }
            next
        }
        /^[[:space:]]*abis:[[:space:]]*[^[:space:]]/ {
            sub(/^[[:space:]]*abis:[[:space:]]*/, "")
            print
        }
    ' "$1"
}

for required in build.yml release.yml beta.yml; do
    [[ -f "$ROOT/.github/workflows/$required" ]] ||
        fail "missing Tier-2 workflow: .github/workflows/$required"
done

for required in build.yml release.yml beta.yml; do
    workflow="$ROOT/.github/workflows/$required"
    rel=".github/workflows/$required"
    found=0
    while IFS= read -r value; do
        value="${value%%#*}"
        [[ -n "${value//[[:space:]]/}" ]] || continue
        if [[ "$value" == *'$'* ]]; then
            continue
        fi
        found=1
        case "$required" in
            build.yml | release.yml)
                assert_set_equals "$rel" "abis: input" "$value" "${ABIS[*]}"
                ;;
            beta.yml)
                assert_manifest_subset "$rel" "abis: input" "$value"
                ;;
        esac
    done < <(workflow_abi_values "$workflow")
    if ((found == 0)); then
        case "$required" in
            build.yml | release.yml)
                fail "$rel has no literal abis: default; it must equal the manifest set exactly"
                ;;
            beta.yml)
                fail "$rel has no literal abis: value; it must be a non-empty manifest subset"
                ;;
        esac
    fi
done

printf 'abi-list: %d checks OK (%d ABIs: %s)\n' "$PASS" "${#ABIS[@]}" "${ABIS[*]}"
