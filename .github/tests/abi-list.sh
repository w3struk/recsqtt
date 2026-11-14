#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Hermetic test for scripts/check_abi_list.sh. Builds a temp tree with the
# manifest and one minimal consumer file per tier, proves the consistent tree
# passes, then mutates it in the ways the gate must catch: an ABI missing from a
# map, unknown Tier-2 names, omissions from every Tier-2 set (build.yml,
# release.yml, the build_apk loop and copy_apk calls, release-note assets), an
# empty manifest, and the beta subset rule.

set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SUT="$REPO_ROOT/scripts/check_abi_list.sh"
[[ -f "$SUT" ]] || {
    printf 'FAIL: missing %s\n' "$SUT" >&2
    exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}
ok() { pass=$((pass + 1)); }

make_tree() {
    local root="$1"
    mkdir -p "$root/rust-client" "$root/app" "$root/scripts" \
        "$root/.github/scripts" "$root/.github/workflows"

    cat >"$root/rust-client/abis.txt" <<'EOF'
# test manifest
arm64-v8a
armeabi-v7a
x86_64
EOF

    printf 'rust-client/abis.txt\n' >"$root/rust-client/native-inputs.txt"

    cat >"$root/app/build.gradle.kts" <<'EOF'
val abiManifest: File = rootProject.file("rust-client/abis.txt")
fun clientCargoArtifacts(abi: String) {
    val (target, suffix) = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android" to "arm64"
        "armeabi-v7a" -> "armv7-linux-androideabi" to "armv7"
        "x86_64" -> "x86_64-linux-android" to "x86_64"
        else -> error("unsupported ABI")
    }
}
EOF

    cat >"$root/scripts/device_abi.sh" <<'EOF'
#!/usr/bin/env bash
ABI_MANIFEST="$ROOT/rust-client/abis.txt"
EOF

    cat >"$root/rust-client/build_so.sh" <<'EOF'
#!/usr/bin/env bash
# ABI list: rust-client/abis.txt
for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a)
            install_lib "$abi" aarch64-linux-android arm64
            ;;
        armeabi-v7a)
            install_lib "$abi" armv7-linux-androideabi armv7
            ;;
        x86_64)
            install_lib "$abi" x86_64-linux-android x86_64
            ;;
        *)
            exit 1
            ;;
    esac
done
EOF

    cat >"$root/rust-client/build_android_abis.sh" <<'EOF'
#!/usr/bin/env bash
# ABI list: rust-client/abis.txt
target_for_abi() {
    case "$1" in
        arm64-v8a) printf 'aarch64-linux-android\n' ;;
        armeabi-v7a) printf 'armv7-linux-androideabi\n' ;;
        x86_64) printf 'x86_64-linux-android\n' ;;
        *) return 1 ;;
    esac
}

target_dir_for_abi() {
    case "$1" in
        arm64-v8a) printf 'arm64\n' ;;
        armeabi-v7a) printf 'armv7\n' ;;
        x86_64) printf 'x86_64\n' ;;
        *) return 1 ;;
    esac
}
EOF

    cat >"$root/scripts/native_client_provenance.sh" <<'EOF'
#!/usr/bin/env bash
ABI_MANIFEST="$ROOT/rust-client/abis.txt"
abi_target() {
    case "$1" in
        arm64-v8a) printf 'aarch64-linux-android\n' ;;
        armeabi-v7a) printf 'armv7-linux-androideabi\n' ;;
        x86_64) printf 'x86_64-linux-android\n' ;;
        *) return 1 ;;
    esac
}

abi_elf_class() {
    case "$1" in
        arm64-v8a) printf '2\n' ;;
        armeabi-v7a) printf '1\n' ;;
        x86_64) printf '2\n' ;;
        *) return 1 ;;
    esac
}

abi_elf_machine() {
    case "$1" in
        arm64-v8a) printf '183\n' ;;
        armeabi-v7a) printf '40\n' ;;
        x86_64) printf '62\n' ;;
        *) return 1 ;;
    esac
}

abi_target_dir() {
    case "$1" in
        arm64-v8a) printf 'arm64\n' ;;
        armeabi-v7a) printf 'armv7\n' ;;
        x86_64) printf 'x86_64\n' ;;
        *) return 1 ;;
    esac
}

valid_abi() {
    case "$1" in
        arm64-v8a | armeabi-v7a | x86_64) return 0 ;;
        *) return 1 ;;
    esac
}
EOF

    cat >"$root/scripts/build_apk.sh" <<'EOF'
#!/usr/bin/env bash
for abi in universal arm64-v8a armeabi-v7a x86_64; do
    printf '%s\n' "$abi"
done
copy_apk universal
copy_apk arm64-v8a
copy_apk armeabi-v7a
copy_apk x86_64
EOF

    cat >"$root/.github/scripts/collect-release-assets.sh" <<'EOF'
#!/usr/bin/env bash
abis=(universal arm64-v8a armeabi-v7a)
EOF

    cat >"$root/.github/scripts/release-note.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' \
    "- recsqtt-$version-universal.apk" \
    "- recsqtt-$version-arm64-v8a.apk" \
    "- recsqtt-$version-armeabi-v7a.apk"
EOF

    cat >"$root/.github/workflows/build.yml" <<'EOF'
jobs:
  build:
    inputs:
      abis:
        description: 'Comma-separated ABI list'
        required: false
        default: 'arm64-v8a,armeabi-v7a,x86_64'
        type: string
EOF

    cat >"$root/.github/workflows/release.yml" <<'EOF'
jobs:
  publish:
    with:
      abis: arm64-v8a,armeabi-v7a,x86_64
EOF

    cat >"$root/.github/workflows/beta.yml" <<'EOF'
jobs:
  beta:
    with:
      abis: arm64-v8a
EOF
}

run_gate() {
    local root="$1"
    set +e
    GATE_OUTPUT="$(bash "$SUT" --root "$root" 2>&1)"
    GATE_RC=$?
    set -e
}

expect_pass() {
    local name="$1" root="$2"
    run_gate "$root"
    if [[ "$GATE_RC" -ne 0 ]]; then
        printf '%s\n' "$GATE_OUTPUT" >&2
        fail "$name: expected the gate to pass, got exit $GATE_RC"
    fi
    ok
}

expect_fail() {
    local name="$1" root="$2" needle="$3"
    run_gate "$root"
    if [[ "$GATE_RC" -eq 0 ]]; then
        fail "$name: expected the gate to fail, got exit 0"
    fi
    grep -qF -- "$needle" <<<"$GATE_OUTPUT" ||
        fail "$name: failure message does not mention '$needle'; got: $GATE_OUTPUT"
    ok
}

# 1. Consistent tree passes (beta.yml is the subset case).
make_tree "$TMP/pass"
expect_pass "consistent tree" "$TMP/pass"

# 2. An ABI missing from one Tier-1 map fails, naming the ABI and the file.
make_tree "$TMP/missing-map"
sed -i '/"x86_64" ->/d' "$TMP/missing-map/app/build.gradle.kts"
expect_fail "missing map entry" "$TMP/missing-map" "'x86_64'"

# 2b. valid_abi() is a Tier-1 map too: a new ABI must not reach runtime and
#     die with "unsupported ABI" after passing this gate.
make_tree "$TMP/missing-valid-abi"
sed -i 's/arm64-v8a | armeabi-v7a | x86_64/arm64-v8a | armeabi-v7a/' "$TMP/missing-valid-abi/scripts/native_client_provenance.sh"
expect_fail "missing valid_abi entry" "$TMP/missing-valid-abi" "valid_abi() has no entry for 'x86_64'"

# 2c. A commented-out map entry does not satisfy the Tier-1 check.
make_tree "$TMP/comment-map"
sed -i 's#^        "x86_64" -> .*#        // "x86_64" -> "x86_64-linux-android" to "x86_64"#' \
    "$TMP/comment-map/app/build.gradle.kts"
expect_fail "commented map entry" "$TMP/comment-map" "'x86_64'"

# 3. A Tier-2 loop naming an ABI outside the manifest fails.
make_tree "$TMP/unknown-tier2"
sed -i 's/x86_64; do/x86_64 riscv64; do/' "$TMP/unknown-tier2/scripts/build_apk.sh"
expect_fail "unknown Tier-2 ABI" "$TMP/unknown-tier2" "riscv64"

# 4. A manifest with only comments is empty.
make_tree "$TMP/empty"
printf '# no ABIs here\n' >"$TMP/empty/rust-client/abis.txt"
expect_fail "empty manifest" "$TMP/empty" "lists no ABIs"

# 5. build.yml must cover the manifest exactly.
make_tree "$TMP/build-yml-omit"
sed -i 's/arm64-v8a,armeabi-v7a,x86_64/arm64-v8a,armeabi-v7a/' "$TMP/build-yml-omit/.github/workflows/build.yml"
expect_fail "build.yml omission" "$TMP/build-yml-omit" "missing [x86_64]"

# 6. release.yml must cover the manifest exactly.
make_tree "$TMP/release-yml-omit"
sed -i 's/arm64-v8a,armeabi-v7a,x86_64/arm64-v8a,armeabi-v7a/' "$TMP/release-yml-omit/.github/workflows/release.yml"
expect_fail "release.yml omission" "$TMP/release-yml-omit" "missing [x86_64]"

# 7. The build_apk loop must equal the manifest plus universal.
make_tree "$TMP/apk-loop-omit"
sed -i 's/x86_64; do/; do/' "$TMP/apk-loop-omit/scripts/build_apk.sh"
expect_fail "build_apk loop omission" "$TMP/apk-loop-omit" "missing [x86_64]"

# 8. copy_apk calls must equal the same set as the loop.
make_tree "$TMP/copy-apk-omit"
sed -i '/^copy_apk x86_64$/d' "$TMP/copy-apk-omit/scripts/build_apk.sh"
expect_fail "copy_apk omission" "$TMP/copy-apk-omit" "missing [x86_64]"

# 9. release-note assets must equal the collect set.
make_tree "$TMP/release-note-omit"
sed -i '/recsqtt-[$]version-armeabi-v7a[.]apk/d' "$TMP/release-note-omit/.github/scripts/release-note.sh"
expect_fail "release-note omission" "$TMP/release-note-omit" "missing [armeabi-v7a]"

# 9b. collect-release-assets must equal manifest minus x86_64 plus universal.
make_tree "$TMP/collect-omit"
sed -i 's/abis=(universal arm64-v8a armeabi-v7a)/abis=(universal arm64-v8a)/' "$TMP/collect-omit/.github/scripts/collect-release-assets.sh"
expect_fail "collect omission" "$TMP/collect-omit" "missing [armeabi-v7a]"

# 10. beta.yml may pass a subset, but only manifest ABIs.
make_tree "$TMP/beta-subset"
sed -i 's/abis: arm64-v8a/abis: armeabi-v7a/' "$TMP/beta-subset/.github/workflows/beta.yml"
expect_pass "beta subset" "$TMP/beta-subset"

make_tree "$TMP/beta-universal"
sed -i 's/abis: arm64-v8a/abis: universal/' "$TMP/beta-universal/.github/workflows/beta.yml"
expect_fail "beta universal" "$TMP/beta-universal" "universal"

# 11. The real tree must be consistent too: this test runs in the CI fixture
#     gate, so the gate is enforced on the repository and not only on fixtures.
expect_pass "repository tree" "$REPO_ROOT"

printf 'abi-list test: %d cases OK\n' "$pass"
