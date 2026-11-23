#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Source-only guard for the musl phases of the server build.
#
# cargo-zigbuild creates the zig cc/c++/ar wrappers and linker variables for its
# own child process, but it uses `add_env_if_missing`: anything already exported
# in the caller's shell wins. A stale CC_* or CMAKE_TOOLCHAIN_FILE therefore
# silently replaces the zig toolchain and the final link can produce a dynamic,
# glibc-hosted binary that is not a musl payload.
#
# MUSL_ENV_UNSET is the list spliced into `env -u ...` before every
# cargo-zigbuild invocation, so checks and the release build see the same
# environment. `+toolchain` is banned for a related reason: it makes
# cargo-zigbuild lose its zig environment entirely and cargo falls back to a
# plain (non-zig) build.
#
# The list is deliberately closed. CFLAGS, LDFLAGS, CPATH, LIBRARY_PATH,
# PKG_CONFIG_* and BINDGEN_EXTRA_CLANG_ARGS are documented cargo-zigbuild knobs
# and stay visible, so a build can still point zig at system headers or a
# sysroot; scrubbing them would remove a supported escape hatch. The fixtures
# pin both halves: the listed variables must be gone, CFLAGS must pass through.
#
# The file is sourced, never executed: it must not call exit and must not change
# the caller's shell options. Failures are returned as a status, and the caller
# decides whether to stop.
# The scrub list is bound to the one target the server ships. A second target
# needs its own list here; otherwise CC_<its triple> would survive the scrub and
# `.github/tests/zig-env.sh` pins the exact contents, so a change is deliberate.
ZIG_ENV_MUSL_TARGET="x86_64-unknown-linux-musl"
_zig_env_suffix="${ZIG_ENV_MUSL_TARGET//-/_}"
_zig_env_upper="${_zig_env_suffix^^}"
# shellcheck disable=SC2034 # public contract: the caller splices it into `env`
MUSL_ENV_UNSET=(
  -u "CC_${_zig_env_suffix}"
  -u "CXX_${_zig_env_suffix}"
  -u "AR_${_zig_env_suffix}"
  -u "RANLIB_${_zig_env_suffix}"
  -u "CARGO_TARGET_${_zig_env_upper}_LINKER"
  -u CMAKE_TOOLCHAIN_FILE
  -u "CMAKE_TOOLCHAIN_FILE_${_zig_env_suffix}"
  -u TARGET_CMAKE_TOOLCHAIN_FILE
)
unset _zig_env_suffix _zig_env_upper

# The pins come from scripts/toolchain.env, which the caller sources first. A
# missing pin is a caller bug, not a "use the installed version" case.
require_cargo_zigbuild() {
  local pin="${CARGO_ZIGBUILD_VERSION:?CARGO_ZIGBUILD_VERSION must come from scripts/toolchain.env}"
  local out
  if ! out="$(cargo-zigbuild --version 2>/dev/null)"; then
    printf 'Error: cargo-zigbuild not found.\n  Install it with: cargo install cargo-zigbuild --version %s --locked\n' "$pin" >&2
    return 1
  fi
  # `cargo-zigbuild --version` prints `cargo-zigbuild <version>`; strip the
  # prefix and take the first token so a suffix cannot become the version.
  out="${out#cargo-zigbuild }"
  out="${out%% *}"
  if [[ "$out" != "$pin" ]]; then
    printf 'Error: cargo-zigbuild %s is installed, %s is pinned.\n  Fix: cargo install cargo-zigbuild --version %s --locked\n' \
      "$out" "$pin" "$pin" >&2
    return 1
  fi
}

require_zig() {
  local pin="${ZIG_VERSION:?ZIG_VERSION must come from scripts/toolchain.env}"
  local out
  if ! out="$(zig version 2>/dev/null)"; then
    printf 'Error: zig not found.\n  Download it from https://ziglang.org/download/%s/\n' "$pin" >&2
    return 1
  fi
  if [[ "$out" != "$pin" ]]; then
    printf 'Error: zig %s is installed, %s is pinned.\n  Download it from https://ziglang.org/download/%s/\n' \
      "$out" "$pin" "$pin" >&2
    return 1
  fi
}
