#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# shellcheck shell=bash

# Sourceable helper that resolves the Android SDK and NDK on Linux.
# Usage: source "$ROOT/scripts/android_env.sh" || exit 1

if [[ -z "${CSQTT_PROJECT_ROOT:-}" ]]; then
    CSQTT_PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
fi
export CSQTT_PROJECT_ROOT

# Android NDK pin. Optional here: without the file the helper still works, but
# falls back to an explicitly requested NDK or (with ALLOW_NEWEST_NDK=1) the
# newest installed one.
_CSQTT_TOOLCHAIN_ENV="$CSQTT_PROJECT_ROOT/scripts/toolchain.env"
if [[ -f "$_CSQTT_TOOLCHAIN_ENV" ]]; then
    # shellcheck source=/dev/null
    source "$_CSQTT_TOOLCHAIN_ENV"
fi

_csqtt_is_dir() {
    [[ -n "${1:-}" && -d "$1" ]]
}

_csqtt_sdk_from_local_properties() {
    local file="$CSQTT_PROJECT_ROOT/local.properties"
    [[ -f "$file" ]] || return 1
    local value
    value="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$file" | sed -n '1p')"
    [[ -n "$value" ]] || return 1
    value="${value//\\:/:}"
    value="${value//\\\\/\\}"
    value="${value%/}"
    _csqtt_is_dir "$value" || return 1
    printf '%s\n' "$value"
}

_csqtt_find_sdk() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        if _csqtt_is_dir "$candidate"; then
            printf '%s\n' "${candidate%/}"
            return 0
        fi
    done
    if candidate="$(_csqtt_sdk_from_local_properties)"; then
        printf '%s\n' "$candidate"
        return 0
    fi
    for candidate in \
        "$HOME/Android/Sdk" \
        "$HOME/.android-sdk" \
        "$HOME/android-sdk" \
        "/opt/android-sdk" \
        "/usr/lib/android-sdk"; do
        if _csqtt_is_dir "$candidate"; then
            printf '%s\n' "${candidate%/}"
            return 0
        fi
    done
    return 1
}

_csqtt_ndk_has_toolchain() {
    [[ -d "$1/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]
}

# Explicitly requested NDK: always wins over the pin.
_csqtt_explicit_ndk() {
    local candidate
    for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK_HOME:-}"; do
        if _csqtt_ndk_has_toolchain "${candidate%/}"; then
            printf '%s\n' "${candidate%/}"
            return 0
        fi
    done
    return 1
}

# Pinned NDK from scripts/toolchain.env, only when the SDK actually has it.
_csqtt_pinned_ndk() {
    local sdk="$1" pin="${ANDROID_NDK_VERSION:-}"
    [[ -n "$pin" ]] || return 1
    _csqtt_ndk_has_toolchain "$sdk/ndk/$pin" || return 1
    printf '%s\n' "$sdk/ndk/$pin"
}

_csqtt_newest_ndk() {
    local sdk="$1" candidate
    [[ -d "$sdk/ndk" ]] || return 1
    while IFS= read -r candidate; do
        if _csqtt_ndk_has_toolchain "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done < <(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort -Vr)
    return 1
}

if ! CSQTT_SDK_PATH="$(_csqtt_find_sdk)"; then
    printf 'error: Android SDK not found.\n' >&2
    printf '       Set ANDROID_HOME/ANDROID_SDK_ROOT, add sdk.dir to local.properties,\n' >&2
    printf '       or install the SDK to ~/Android/Sdk.\n' >&2
    return 1 2>/dev/null || exit 1
fi

if ! CSQTT_NDK_PATH="$(_csqtt_explicit_ndk)"; then
    _csqtt_ndk_pin="${ANDROID_NDK_VERSION:-}"
    if CSQTT_NDK_PATH="$(_csqtt_pinned_ndk "$CSQTT_SDK_PATH")"; then
        :
    elif [[ "${ALLOW_NEWEST_NDK:-0}" == 1 ]]; then
        if [[ -n "$_csqtt_ndk_pin" ]]; then
            printf 'warning: pinned Android NDK %s not found under %s/ndk; ALLOW_NEWEST_NDK=1, using the newest installed NDK.\n' \
                "$_csqtt_ndk_pin" "$CSQTT_SDK_PATH" >&2
        else
            printf 'warning: ANDROID_NDK_VERSION is not pinned (scripts/toolchain.env); ALLOW_NEWEST_NDK=1, using the newest installed NDK.\n' >&2
        fi
        if ! CSQTT_NDK_PATH="$(_csqtt_newest_ndk "$CSQTT_SDK_PATH")"; then
            printf 'error: Android NDK not found under %s/ndk.\n' "$CSQTT_SDK_PATH" >&2
            return 1 2>/dev/null || exit 1
        fi
    else
        if [[ -n "$_csqtt_ndk_pin" ]]; then
            printf 'error: pinned Android NDK %s not found under %s/ndk.\n' "$_csqtt_ndk_pin" "$CSQTT_SDK_PATH" >&2
            printf '       Install it, set ANDROID_NDK_HOME/ANDROID_NDK_ROOT, or set ALLOW_NEWEST_NDK=1 to use the newest installed NDK.\n' >&2
        else
            printf 'error: no pinned Android NDK (scripts/toolchain.env) and no explicit NDK override.\n' >&2
            printf '       Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT, install an NDK under %s/ndk, or set ALLOW_NEWEST_NDK=1.\n' "$CSQTT_SDK_PATH" >&2
        fi
        return 1 2>/dev/null || exit 1
    fi
    unset _csqtt_ndk_pin
else
    if [[ -n "${ANDROID_NDK_VERSION:-}" && "$(basename "$CSQTT_NDK_PATH")" != "$ANDROID_NDK_VERSION" ]]; then
        printf 'warning: explicit Android NDK %s differs from the pinned %s (scripts/toolchain.env).\n' \
            "$(basename "$CSQTT_NDK_PATH")" "$ANDROID_NDK_VERSION" >&2
    fi
fi

CSQTT_NDK_VERSION="$(basename "$CSQTT_NDK_PATH")"
export CSQTT_SDK_PATH CSQTT_NDK_PATH CSQTT_NDK_VERSION
export ANDROID_HOME="$CSQTT_SDK_PATH"
export ANDROID_SDK_ROOT="$CSQTT_SDK_PATH"
export ANDROID_NDK_HOME="$CSQTT_NDK_PATH"
export ANDROID_NDK_ROOT="$CSQTT_NDK_PATH"

_csqtt_cmake_bin="$(
    find "$CSQTT_SDK_PATH/cmake" -mindepth 2 -maxdepth 2 -type d -name bin 2>/dev/null |
        LC_ALL=C sort -Vr | sed -n '1p'
)"
if [[ -n "$_csqtt_cmake_bin" && -d "$_csqtt_cmake_bin" ]]; then
    case ":$PATH:" in
        *":$_csqtt_cmake_bin:"*) ;;
        *) PATH="$_csqtt_cmake_bin:$PATH" ;;
    esac
fi

_csqtt_llvm_bin="$CSQTT_NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ -d "$_csqtt_llvm_bin" ]]; then
    case ":$PATH:" in
        *":$_csqtt_llvm_bin:"*) ;;
        *) PATH="$_csqtt_llvm_bin:$PATH" ;;
    esac
fi

export PATH

printf 'Using SDK: %s\n' "$CSQTT_SDK_PATH"
printf 'Using NDK: %s (%s)\n' "$CSQTT_NDK_PATH" "$CSQTT_NDK_VERSION"
