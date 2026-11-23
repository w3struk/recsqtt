#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

# Writes or verifies the provenance manifest of the server binaries published as
# a separate release asset (never inside the APK). Both the binaries and the
# manifest live under app/build/generated/.
# Usage: server_asset_provenance.sh <write|verify> [--root DIR] [--assets recsqtt-linux-amd64]
#                                         [--assets-dir DIR] [--manifest FILE]
#                                         [--features a,b] [--strict-toolchain]

set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=""
ALL_ASSETS=(recsqtt-linux-amd64)
ASSETS=("${ALL_ASSETS[@]}")
ASSET_DIR=""
MANIFEST=""
FEATURES=()
ALLOWED_FEATURES=()
STRICT_TOOLCHAIN=0

SCHEMA="csqtt.server-asset-provenance.v5"
MARKER="CSQTT_RUST_SERVER_PRODUCTION_V1"
PRODUCER="rust-server/build_linux.sh"

usage() {
    printf 'Usage: %s <write|verify> [--root DIR] [--assets %s]\n' "$(basename "$0")" "${ALL_ASSETS[*]}" >&2
    printf '       [--assets-dir DIR] [--manifest FILE] [--features a,b]\n' >&2
    printf '       [--allow-feature NAME]... [--strict-toolchain]\n' >&2
    printf '       --strict-toolchain (or CSQTT_STRICT_TOOLCHAIN=1) compares the\n' >&2
    printf '       recorded versions to the pins in scripts/toolchain.env and fails\n' >&2
    printf '       on drift; without it a mismatch only warns.\n' >&2
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

select_assets() {
    # Check the whole value: splitting would hide trailing commas or newlines.
    [[ "$1" == "recsqtt-linux-amd64" ]] || die "--assets must be exactly recsqtt-linux-amd64"
    ASSETS=("$1")
}

while (($#)); do
    case "$1" in
        write | verify) MODE="$1" ;;
        --root)
            (($# >= 2)) || die "--root requires a value"
            ROOT="$2"
            shift
            ;;
        --assets)
            (($# >= 2)) || die "--assets requires a value"
            select_assets "$2"
            shift
            ;;
        --assets=*)
            select_assets "${1#--assets=}"
            ;;
        --assets-dir)
            (($# >= 2)) || die "--assets-dir requires a value"
            ASSET_DIR="$2"
            shift
            ;;
        --assets-dir=*) ASSET_DIR="${1#--assets-dir=}" ;;
        --features)
            (($# >= 2)) || die "--features requires a value"
            IFS=',' read -r -a FEATURES <<<"$2"
            shift
            ;;
        --features=*)
            IFS=',' read -r -a FEATURES <<<"${1#--features=}"
            ;;
        --allow-feature)
            (($# >= 2)) || die "--allow-feature requires a value"
            ALLOWED_FEATURES+=("$2")
            shift
            ;;
        --allow-feature=*) ALLOWED_FEATURES+=("${1#--allow-feature=}") ;;
        --strict-toolchain) STRICT_TOOLCHAIN=1 ;;
        --manifest)
            (($# >= 2)) || die "--manifest requires a value"
            MANIFEST="$2"
            shift
            ;;
        --manifest=*) MANIFEST="${1#--manifest=}" ;;
        -h | --help)
            usage
            exit 0
            ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

[[ -n "$MODE" ]] || {
    usage
    exit 2
}
ROOT="$(cd -- "$ROOT" && pwd)"

if [[ "${CSQTT_STRICT_TOOLCHAIN:-0}" == 1 ]]; then
    STRICT_TOOLCHAIN=1
fi

GENERATED_ROOT="$ROOT/app/build/generated"
[[ -n "$ASSET_DIR" ]] || ASSET_DIR="$GENERATED_ROOT/assets"
[[ -n "$MANIFEST" ]] || MANIFEST="$GENERATED_ROOT/provenance/csqtt-server.json"
CARGO_TOML="$ROOT/rust-server/Cargo.toml"
CARGO_LOCK="$ROOT/rust-server/Cargo.lock"
WIRE_REVISION_SOURCE="$ROOT/rust-server/main.rs"
TOOLCHAIN_ENV="$ROOT/scripts/toolchain.env"

valid_asset() {
    case "$1" in
        recsqtt-linux-amd64) return 0 ;;
        *) return 1 ;;
    esac
}

asset_target() {
    case "$1" in
        recsqtt-linux-amd64) printf 'x86_64-unknown-linux-musl\n' ;;
    esac
}

asset_elf_class() {
    case "$1" in
        recsqtt-linux-amd64) printf '2\n' ;;
    esac
}

asset_elf_machine() {
    case "$1" in
        recsqtt-linux-amd64) printf '62\n' ;;
    esac
}

asset_path() {
    printf '%s/%s\n' "$ASSET_DIR" "$1"
}

cargo_path() {
    printf '%s/build/csqtt-uring-linux-%s/%s/release/csqtt\n' "$ROOT" "$1" "$(asset_target "$1")"
}

sha256_file() {
    sha256sum "$1" | awk '{print toupper($1)}'
}

require_file() {
    [[ -f "$1" ]] || die "$2 not found: $1"
    [[ -s "$1" ]] || die "$2 is empty: $1"
}

# --- Informational toolchain recording -------------------------------------
# The manifest records what compiled the binary. By default these values are
# informational: drift between the manifest and the current host only warns.
# Strict mode (--strict-toolchain or CSQTT_STRICT_TOOLCHAIN=1) additionally
# compares every recorded version to its pin in scripts/toolchain.env, because
# build and verify normally run in the same CI job and would otherwise agree
# with each other while drifting away from the single-source pins.
#
# Probing happens on the machine that runs the script, so every probe failure
# degrades to "unknown" and never blocks the write.
#
# Hermetic probing for tests: set CSQTT_PROVENANCE_RUSTC_VERSION,
# CSQTT_PROVENANCE_CARGO_ZIGBUILD_VERSION and CSQTT_PROVENANCE_ZIG_VERSION.
# Values are recorded verbatim and mark the source as "env-override" (or
# "mixed" when only some are set).

load_pinned_rust_toolchain() {
    [[ -f "$TOOLCHAIN_ENV" ]] || return 1
    (
        # shellcheck source=/dev/null
        source "$TOOLCHAIN_ENV"
        [[ -n "${RUST_TOOLCHAIN:-}" ]] || exit 1
        printf '%s\n' "$RUST_TOOLCHAIN"
    )
}

# All three pins, one per line. Strict verification compares the manifest to
# these values; a missing pin file is a hard failure there, never a fallback to
# whatever this host happens to have installed.
load_toolchain_pins() {
    [[ -f "$TOOLCHAIN_ENV" ]] || return 1
    (
        # shellcheck source=/dev/null
        source "$TOOLCHAIN_ENV"
        printf '%s\n%s\n%s\n' \
            "${RUST_TOOLCHAIN:-}" "${CARGO_ZIGBUILD_VERSION:-}" "${ZIG_VERSION:-}"
    )
}

# "rustc 1.97.1 (hash date)" and "1.97.1 (hash date)" both mean the 1.97.1
# channel; the trailing build hash belongs to the distribution, not the pin.
normalize_rustc_version() {
    local value="$1"
    value="${value#rustc }"
    printf '%s\n' "${value%% *}"
}

normalize_toolchain_version() {
    local field="$1" value="$2"
    case "$field" in
        rustc) normalize_rustc_version "$value" ;;
        *) printf '%s\n' "${value##* }" ;;
    esac
}

probe_output() {
    local out
    out="$("$@" 2>/dev/null)" || return 1
    out="${out%%$'\n'*}"
    out="${out%$'\r'}"
    [[ -n "$out" ]] || return 1
    printf '%s\n' "$out"
}

probe_rustc_version() {
    local override="${CSQTT_PROVENANCE_RUSTC_VERSION:-}" output
    if [[ -n "$override" ]]; then
        printf '%s\n' "$override"
        return 0
    fi
    if [[ -n "$PINNED_RUST_TOOLCHAIN" ]] &&
        output="$(probe_output rustc "+$PINNED_RUST_TOOLCHAIN" --version)"; then
        :
    elif output="$(probe_output rustc --version)"; then
        :
    else
        printf 'unknown\n'
        return 0
    fi
    # "rustc 1.97.1 (hash date)" -> "rustc 1.97.1": the trailing build hash
    # changes with the compiler distribution, not with the pinned channel.
    awk 'NF >= 2 { print $1 " " $2; next } { print $1 }' <<<"$output"
}

probe_cargo_zigbuild_version() {
    local override="${CSQTT_PROVENANCE_CARGO_ZIGBUILD_VERSION:-}" output
    if [[ -n "$override" ]]; then
        printf '%s\n' "$override"
        return 0
    fi
    if ! output="$(probe_output cargo-zigbuild --version)"; then
        printf 'unknown\n'
        return 0
    fi
    awk '{ print $NF }' <<<"$output"
}

probe_zig_version() {
    local override="${CSQTT_PROVENANCE_ZIG_VERSION:-}"
    if [[ -n "$override" ]]; then
        printf '%s\n' "$override"
        return 0
    fi
    if ! probe_output zig version; then
        printf 'unknown\n'
    fi
}

toolchain_source_label() {
    local overrides=0
    [[ -z "${CSQTT_PROVENANCE_RUSTC_VERSION:-}" ]] || overrides=$((overrides + 1))
    [[ -z "${CSQTT_PROVENANCE_CARGO_ZIGBUILD_VERSION:-}" ]] || overrides=$((overrides + 1))
    [[ -z "${CSQTT_PROVENANCE_ZIG_VERSION:-}" ]] || overrides=$((overrides + 1))
    if ((overrides == 3)); then
        printf 'env-override\n'
    elif ((overrides > 0)); then
        printf 'mixed\n'
    elif [[ -n "$PINNED_RUST_TOOLCHAIN" ]]; then
        printf 'toolchain.env\n'
    else
        printf 'path\n'
    fi
}

resolve_toolchain_versions() {
    PROBED_RUSTC="$(probe_rustc_version)"
    PROBED_CARGO_ZIGBUILD="$(probe_cargo_zigbuild_version)"
    PROBED_ZIG="$(probe_zig_version)"
    TOOLCHAIN_SOURCE="$(toolchain_source_label)"
}

warn_unknown_toolchain() {
    local name value
    for name in rustc cargoZigbuild zig; do
        case "$name" in
            rustc) value="$PROBED_RUSTC" ;;
            cargoZigbuild) value="$PROBED_CARGO_ZIGBUILD" ;;
            zig) value="$PROBED_ZIG" ;;
        esac
        if [[ "$value" == "unknown" ]]; then
            printf 'warning: could not determine %s version; recording "unknown" in the provenance manifest\n' "$name" >&2
        fi
    done
}

PINNED_RUST_TOOLCHAIN="$(load_pinned_rust_toolchain || true)"
PROBED_RUSTC="unknown"
PROBED_CARGO_ZIGBUILD="unknown"
PROBED_ZIG="unknown"
TOOLCHAIN_SOURCE="unknown"

cargo_package_field() {
    local key="$1"
    require_file "$CARGO_TOML" "Server Cargo.toml"
    awk -v key="$key" '
        /^\[package\][[:space:]]*$/ { inside = 1; next }
        inside && /^\[/ { exit }
        inside && $1 == key {
            line = $0
            sub(/^[^=]*=[[:space:]]*"/, "", line)
            sub(/".*$/, "", line)
            print line
            exit
        }
    ' "$CARGO_TOML"
}

wire_protocol_revision() {
    local revision
    require_file "$WIRE_REVISION_SOURCE" "Wire protocol revision source"
    revision="$(sed -n 's/.*WIRE_PROTOCOL_REVISION[[:space:]]*:[[:space:]]*&str[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$WIRE_REVISION_SOURCE" | sed -n '1p')"
    [[ -n "$revision" ]] || die "Wire protocol revision is missing"
    printf '%s\n' "$revision"
}

# Форматы, которые встраиваются в бинарник через include_str!. Новый формат
# ассета добавляется сюда — и сразу попадает в хеш провенанса и в проверку дерева.
WEB_ASSET_GLOBS=('*.css' '*.js' '*.html')

is_web_asset() {
    local rel="$1" glob
    for glob in "${WEB_ASSET_GLOBS[@]}"; do
        # shellcheck disable=SC2053  # глоб справа — намеренное сравнение по шаблону
        [[ "$rel" == $glob ]] && return 0
    done
    return 1
}

build_input_files() {
    local prefix rel
    printf '%s\n' \
        rust-server/build_linux.sh \
        rust-server/build_linux_variants.sh \
        rust-server/Cargo.toml \
        rust-server/Cargo.lock \
        rust-server/rust-toolchain.toml \
        scripts/zig_env.sh \
        rust-server/ru_apps_preset.json \
        rust-server/data/domains_ru.txt \
        rust-server/data/ips_ru.txt \
        scripts/server_asset_provenance.sh \
        scripts/toolchain.env

    find "$ROOT/rust-server" -maxdepth 1 -type f -name '*.rs' -printf 'rust-server/%P\n'
    find "$ROOT/rust-server/data" -maxdepth 1 -type f -name '*.txt' -printf 'rust-server/data/%P\n'

    # Веб-ассеты панели скомпилированы в бинарник через include_str!, поэтому их
    # правка обязана менять хеш провенанса: иначе установленный сервер остался бы
    # со старым UI при «свежих» исходниках. Хешируются только встраиваемые
    # форматы; допустимость дерева проверяет verify_web_asset_tree.
    while IFS= read -r rel; do
        if is_web_asset "$rel"; then
            printf '%s\n' "$rel"
        fi
    done < <(find "$ROOT/rust-server/web" -type f -printf 'rust-server/web/%P\n')
    find "$ROOT/shared" -maxdepth 1 -type f -name '*.rs' -printf 'shared/%P\n'

    # Cargo config files, same reasoning as on the client side: they set the build
    # target and the rustflags, and only existing files may be listed.
    for prefix in ".cargo" "rust-server/.cargo"; do
        [[ -d "$ROOT/$prefix" ]] || continue
        ( cd "$ROOT/$prefix" && find . -type f -printf "$prefix/%P\n" )
    done
}

# Допустимость дерева веб-ассетов проверяется отдельным шагом, а не внутри
# build_input_files: `die` из конвейера (`build_input_files | sort`) убивает
# только подоболочку конвейера, скрипт продолжил бы работу с усечённым списком
# входов. Остатки редакторов и слияний хеш не меняют, незнакомый формат — ошибка.
verify_web_asset_tree() {
    local rel
    [[ -d "$ROOT/rust-server/web" ]] || die "Web assets directory is missing: rust-server/web"
    while IFS= read -r rel; do
        is_web_asset "$rel" && continue
        case "$rel" in
            .DS_Store | */.DS_Store | .* | */.* | *~ | *.orig | *.rej | *.swp | *.bak) continue ;;
            *) die "Unexpected file under rust-server/web (add its extension to WEB_ASSET_GLOBS): $rel" ;;
        esac
    done < <(find "$ROOT/rust-server/web" -type f -printf 'rust-server/web/%P\n')
}

canonical_build_input_hash() {
    local list tmp rel
    list="$(mktemp)"
    tmp="$(mktemp)"
    trap "rm -f '$list' '$tmp'" EXIT
    build_input_files | LC_ALL=C sort -u >"$list"
    [[ -s "$list" ]] || die "server build input list is empty"

    while IFS= read -r rel; do
        [[ -n "$rel" ]] || continue
        require_file "$ROOT/$rel" "Server build input"
        printf '%s:%s\n' "$rel" "$(sha256_file "$ROOT/$rel")"
    done <"$list" >"$tmp"

    sha256_file "$tmp"
    rm -f "$list" "$tmp"
    trap - EXIT
}

read_elf_bytes() {
    ELF_BYTES=()
    mapfile -t ELF_BYTES < <(od -An -v -tu1 -N20 "$1" | tr -s '[:space:]' '\n' | sed '/^$/d')
    ((${#ELF_BYTES[@]} >= 20)) || die "Invalid ELF header: $1"
}

assert_server_binary() {
    local asset="$1" revision="$2"
    local path class machine

    path="$(asset_path "$asset")"
    class="$(asset_elf_class "$asset")"
    machine="$(asset_elf_machine "$asset")"

    require_file "$path" "Linux server asset $asset"
    read_elf_bytes "$path"
    [[ "${ELF_BYTES[0]}" -eq 127 && "${ELF_BYTES[1]}" -eq 69 && "${ELF_BYTES[2]}" -eq 76 && "${ELF_BYTES[3]}" -eq 70 ]] ||
        die "Linux server asset $asset is not ELF"
    [[ "${ELF_BYTES[4]}" -eq "$class" && "${ELF_BYTES[5]}" -eq 1 ]] ||
        die "ELF class or endianness mismatch for $asset"

    local actual_machine=$((ELF_BYTES[18] + ELF_BYTES[19] * 256))
    [[ "$actual_machine" -eq "$machine" ]] ||
        die "ELF machine mismatch for $asset: expected $machine, got $actual_machine"
    grep -a -q -F -e "$revision" -- "$path" ||
        die "Linux server asset $asset does not expose wire protocol revision $revision"
}

artifact_size() {
    stat -c '%s' "$1"
}

write_manifest() {
    local package_name package_version revision build_input_hash artifacts entry asset size sha target class machine tmp
    require_file "$CARGO_LOCK" "Server Cargo.lock"

    package_name="$(cargo_package_field name)"
    package_version="$(cargo_package_field version)"
    [[ -n "$package_name" && -n "$package_version" ]] || die "Unable to read Rust server package identity"
    revision="$(wire_protocol_revision)"
    build_input_hash="$(canonical_build_input_hash)"
    resolve_toolchain_versions
    warn_unknown_toolchain

    artifacts='[]'
    for asset in "${ASSETS[@]}"; do
        assert_server_binary "$asset" "$revision"
        size="$(artifact_size "$(asset_path "$asset")")"
        sha="$(sha256_file "$(asset_path "$asset")")"
        target="$(asset_target "$asset")"
        class="$(asset_elf_class "$asset")"
        machine="$(asset_elf_machine "$asset")"
        artifacts="$(jq -c \
            --arg assetName "$asset" \
            --arg target "$target" \
            --argjson elfClass "$class" \
            --argjson elfMachine "$machine" \
            --argjson binarySize "$size" \
            --arg binarySha256 "$sha" \
            '. + [{assetName: $assetName, target: $target, elfClass: $elfClass, elfMachine: $elfMachine, binarySize: $binarySize, binarySha256: $binarySha256}]' <<<"$artifacts")"
    done

    mkdir -p "$(dirname "$MANIFEST")"
    tmp="$MANIFEST.tmp.$$"
    jq -n \
        --arg schema "$SCHEMA" \
        --arg marker "$MARKER" \
        --arg producer "$PRODUCER" \
        --arg package "$package_name" \
        --arg version "$package_version" \
        --arg wireProtocolRevision "$revision" \
        --arg buildInputSha256 "$build_input_hash" \
        --arg cargoTomlSha256 "$(sha256_file "$CARGO_TOML")" \
        --arg cargoLockSha256 "$(sha256_file "$CARGO_LOCK")" \
        --arg generatedAtUtc "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" \
        --arg toolchainRustc "$PROBED_RUSTC" \
        --arg toolchainCargoZigbuild "$PROBED_CARGO_ZIGBUILD" \
        --arg toolchainZig "$PROBED_ZIG" \
        --arg toolchainSource "$TOOLCHAIN_SOURCE" \
        --argjson artifacts "$artifacts" \
        --argjson features "$(printf '%s\n' "${FEATURES[@]:-}" | sed '/^$/d' | jq -R . | jq -s .)" \
        '{
            schema: $schema,
            marker: $marker,
            producer: $producer,
            runtime: "rust",
            package: $package,
            version: $version,
            wireProtocolRevision: $wireProtocolRevision,
            buildInputSha256: $buildInputSha256,
            features: $features,
            toolchain: {
                rustc: $toolchainRustc,
                cargoZigbuild: $toolchainCargoZigbuild,
                zig: $toolchainZig,
                source: $toolchainSource
            },
            cargoTomlSha256: $cargoTomlSha256,
            cargoLockSha256: $cargoLockSha256,
            artifacts: $artifacts,
            generatedAtUtc: $generatedAtUtc
        }' >"$tmp"
    mv "$tmp" "$MANIFEST"
    printf 'Wrote Rust server provenance: %s\n' "$MANIFEST"
}

verify_toolchain() {
    local field recorded current pin recorded_norm pin_norm
    local -a pins=()
    resolve_toolchain_versions

    if [[ "$STRICT_TOOLCHAIN" == 1 ]]; then
        # Strict means "match the pins", and the pins live in one file. Without
        # it there is nothing to compare against, so do not guess.
        if [[ ! -f "$TOOLCHAIN_ENV" ]]; then
            die "strict toolchain verification requires scripts/toolchain.env; it is missing: $TOOLCHAIN_ENV"
        fi
        mapfile -t pins < <(load_toolchain_pins)
        [[ -n "${pins[0]:-}" ]] || die "scripts/toolchain.env does not define RUST_TOOLCHAIN"
        [[ -n "${pins[1]:-}" ]] || die "scripts/toolchain.env does not define CARGO_ZIGBUILD_VERSION"
        [[ -n "${pins[2]:-}" ]] || die "scripts/toolchain.env does not define ZIG_VERSION"
    fi

    for field in rustc cargoZigbuild zig; do
        case "$field" in
            rustc)
                current="$PROBED_RUSTC"
                pin="${pins[0]:-}"
                ;;
            cargoZigbuild)
                current="$PROBED_CARGO_ZIGBUILD"
                pin="${pins[1]:-}"
                ;;
            zig)
                current="$PROBED_ZIG"
                pin="${pins[2]:-}"
                ;;
        esac
        recorded="$(jq -r --arg field "$field" '.toolchain[$field] // empty' "$MANIFEST")"
        [[ -n "$recorded" ]] || continue

        if [[ "$STRICT_TOOLCHAIN" != 1 ]]; then
            # Informational only: report manifest-vs-host drift, say nothing
            # about the pins (that would be noise on every local verify).
            if [[ "$recorded" != "unknown" && "$current" != "unknown" && "$recorded" != "$current" ]]; then
                printf 'warning: Rust server provenance toolchain mismatch for %s: recorded %s, current %s\n' \
                    "$field" "$recorded" "$current" >&2
            fi
            continue
        fi

        # The pin is the contract. Build and verify sharing one job is exactly
        # why recorded==current is not sufficient: both can drift together.
        recorded_norm="$(normalize_toolchain_version "$field" "$recorded")"
        pin_norm="$(normalize_toolchain_version "$field" "$pin")"
        if [[ "$recorded_norm" != "$pin_norm" ]]; then
            die "strict toolchain drift for $field: recorded $recorded, current $current, pin $pin"
        fi
        if [[ "$recorded" != "unknown" && "$current" != "unknown" && "$recorded" != "$current" ]]; then
            die "strict toolchain mismatch for $field: recorded $recorded, current $current, pin $pin"
        fi
    done
}

verify_manifest() {
    local package_name package_version revision build_input_hash count expected_count entry asset
    local m_target m_class m_machine m_size m_sha

    require_file "$MANIFEST" "Rust server provenance"

    jq -e \
        --arg schema "$SCHEMA" \
        --arg marker "$MARKER" \
        --arg producer "$PRODUCER" \
        '(.schema == $schema) and
         (.marker == $marker) and
         (.producer == $producer) and
         (.runtime == "rust") and
         ((.features | type) == "array") and
         ((.toolchain | type) == "object") and
         ((.toolchain.rustc | type) == "string") and
         ((.toolchain.cargoZigbuild | type) == "string") and
         ((.toolchain.zig | type) == "string") and
         ((.toolchain.source | type) == "string")' "$MANIFEST" >/dev/null ||
        die "Rust server provenance identity mismatch"

    # Strict mode needs the pin file; check it before other fail-closed steps so
    # a missing pin is reported as such instead of as a source-hash failure.
    verify_toolchain

    # A diagnostics build is a different animal: it sniffs DPI on the wire. It must
    # never pass as a production payload unless the caller opted in explicitly.
    while IFS= read -r recorded; do
        [[ -n "$recorded" ]] || continue
        local allowed=false
        for candidate in ${ALLOWED_FEATURES[@]+"${ALLOWED_FEATURES[@]}"}; do
            [[ "$candidate" == "$recorded" ]] && {
                allowed=true
                break
            }
        done
        [[ "$allowed" == "true" ]] ||
            die "Rust server binary was built with the '$recorded' cargo feature. Re-build without it, or pass --allow-feature=$recorded to accept it deliberately."
    done < <(jq -r '.features[]?' "$MANIFEST")

    package_name="$(cargo_package_field name)"
    package_version="$(cargo_package_field version)"
    revision="$(wire_protocol_revision)"

    [[ "$(jq -r '.package' "$MANIFEST")" == "$package_name" ]] || die "Rust server package name does not match provenance"
    [[ "$(jq -r '.version' "$MANIFEST")" == "$package_version" ]] || die "Rust server version does not match provenance"
    [[ "$(jq -r '.wireProtocolRevision' "$MANIFEST")" == "$revision" ]] ||
        die "Rust server wire protocol revision does not match provenance"

    [[ "$(jq -r '.cargoTomlSha256' "$MANIFEST")" == "$(sha256_file "$CARGO_TOML")" ]] ||
        die "Cargo.toml changed after the production server build"
    [[ "$(jq -r '.cargoLockSha256' "$MANIFEST")" == "$(sha256_file "$CARGO_LOCK")" ]] ||
        die "Cargo.lock changed after the production server build"

    build_input_hash="$(canonical_build_input_hash)"
    [[ "$(jq -r '.buildInputSha256' "$MANIFEST")" == "$build_input_hash" ]] ||
        die "Rust server source changed after the production server build"

    count="$(jq -r '.artifacts | length' "$MANIFEST")"
    expected_count="${#ASSETS[@]}"
    [[ "$count" -eq "$expected_count" ]] ||
        die "Rust server provenance artifact count mismatch"

    for asset in "${ASSETS[@]}"; do
        entry="$(jq -r --arg asset "$asset" '
            [.artifacts[] | select(.assetName == $asset)] as $matches |
            if ($matches | length) != 1 then
                error("Rust server provenance is missing " + $asset)
            else
                $matches[0] | [.target, (.elfClass | tostring), (.elfMachine | tostring), (.binarySize | tostring), (.binarySha256 | ascii_upcase)] | @tsv
            end' "$MANIFEST")"
        IFS=$'\t' read -r m_target m_class m_machine m_size m_sha <<<"$entry"

        assert_server_binary "$asset" "$revision"

        [[ "$m_target" == "$(asset_target "$asset")" ]] || die "Rust server provenance target mismatch for $asset"
        [[ "$m_class" == "$(asset_elf_class "$asset")" ]] || die "Rust server provenance ELF class mismatch for $asset"
        [[ "$m_machine" == "$(asset_elf_machine "$asset")" ]] || die "Rust server provenance ELF machine mismatch for $asset"
        [[ "$m_size" == "$(artifact_size "$(asset_path "$asset")")" ]] || die "Rust server provenance size mismatch for $asset"
        [[ "$m_sha" == "$(sha256_file "$(asset_path "$asset")")" ]] || die "Rust server asset does not match provenance: $asset"
    done

    printf 'Rust server provenance verified: %s\n' "$MARKER"
}

for tool in jq sha256sum stat od mktemp; do
    require_cmd "$tool"
done

for asset in "${ASSETS[@]}"; do
    valid_asset "$asset" || die "unsupported server asset: $asset"
done

for stale_asset in csqtt-linux-arm64 csqtt-linux-armv7; do
    [[ ! -e "$ASSET_DIR/$stale_asset" && ! -L "$ASSET_DIR/$stale_asset" ]] ||
        die "stale ARM server asset: $ASSET_DIR/$stale_asset"
done

verify_web_asset_tree

if [[ "$MODE" == "write" ]]; then
    write_manifest
else
    verify_manifest
fi
