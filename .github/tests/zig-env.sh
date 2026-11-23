#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Section test for scripts/zig_env.sh: the ambient-environment scrub list and
# the pinned toolchain checks. The helper is sourced, never executed, so this
# suite drives it from child shells with fake tool binaries on PATH.
set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$REPO_ROOT/scripts/zig_env.sh"
TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
BIN="$TMP/bin"
mkdir -p -- "$BIN"

cat >"$BIN/cargo-zigbuild" <<'SH'
#!/usr/bin/env bash
printf 'cargo-zigbuild %s\n' "${FAKE_CZ:-0.24.0}"
SH
cat >"$BIN/zig" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "${FAKE_ZIG:-0.17.0}"
SH
chmod 0755 "$BIN/cargo-zigbuild" "$BIN/zig"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}
ok() { printf '[OK] %s\n' "$1"; }

[[ -f "$HELPER" ]] || fail "missing scripts/zig_env.sh"

actual="$(PATH="$BIN:$PATH" bash -c 'source "$1"; printf "%s\n" "${MUSL_ENV_UNSET[@]}"' _ "$HELPER")"
expected="-u
CC_x86_64_unknown_linux_musl
-u
CXX_x86_64_unknown_linux_musl
-u
AR_x86_64_unknown_linux_musl
-u
RANLIB_x86_64_unknown_linux_musl
-u
CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER
-u
CMAKE_TOOLCHAIN_FILE
-u
CMAKE_TOOLCHAIN_FILE_x86_64_unknown_linux_musl
-u
TARGET_CMAKE_TOOLCHAIN_FILE"
[[ "$actual" == "$expected" ]] || fail "MUSL_ENV_UNSET mismatch:
$actual"
ok "MUSL_ENV_UNSET снимает 8 ambient-переменных"

PATH="$BIN:$PATH" CARGO_ZIGBUILD_VERSION=0.24.0 ZIG_VERSION=0.17.0 \
    bash -c 'source "$1"; require_cargo_zigbuild; require_zig' _ "$HELPER" ||
    fail "valid toolchain rejected"
ok "пины принимаются без сообщений"

if out="$(PATH="$BIN:$PATH" FAKE_CZ=0.23.4 CARGO_ZIGBUILD_VERSION=0.24.0 \
    bash -c 'source "$1"; require_cargo_zigbuild' _ "$HELPER" 2>&1)"; then
    fail "cargo-zigbuild 0.23.4 принят против пина 0.24.0"
fi
grep -qF 'cargo install cargo-zigbuild --version 0.24.0 --locked' <<<"$out" ||
    fail "нет команды фикса: $out"
ok "дрейф cargo-zigbuild отклонён с командой установки"

if out="$(PATH="$BIN:$PATH" FAKE_ZIG=0.17.0-dev.2056 ZIG_VERSION=0.17.0 \
    bash -c 'source "$1"; require_zig' _ "$HELPER" 2>&1)"; then
    fail "dev-zig принят против пина 0.17.0"
fi
grep -qF 'https://ziglang.org/download/0.17.0/' <<<"$out" ||
    fail "нет ссылки на загрузку: $out"
ok "дрейф zig отклонён со ссылкой на пин"

PATH="$BIN:$PATH" bash -c 'source "$1"; echo alive' _ "$HELPER" | grep -qx alive ||
    fail "хелпер завершил вызывающий шелл"
ok "source не убивает вызывающий шелл"

printf 'zig-env: все проверки пройдены\n'
