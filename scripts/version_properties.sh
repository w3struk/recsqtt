#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# The only shell parser of version.properties — the single source of truth for
# the app version. Sourced by the build, install and release scripts; the same
# file is read by app/build.gradle.kts.
#
#   source scripts/version_properties.sh
#   name="$(csqtt_version_name "$root")"   # X.Y.Z
#   code="$(csqtt_version_code "$root")"   # integer
#
# Values go to stdout, errors to stderr with a non-zero return.

csqtt_version_property() {
    local root="$1" key="$2" file="$1/version.properties"
    if [[ ! -f "$file" ]]; then
        printf 'version.properties: файл не найден: %s\n' "$file" >&2
        return 1
    fi
    awk -v key="$key" -v file="$file" '
        { line = $0; sub(/\r$/, "", line) }
        line ~ /^[[:space:]]*#/ { next }
        line ~ /^[[:space:]]*$/ { next }
        line ~ ("^[[:space:]]*" key "[[:space:]]*=") {
            value = line
            sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", value)
            sub(/[[:space:]]+$/, "", value)
            count++
            result = value
            next
        }
        END {
            if (count == 0) {
                printf "version.properties: не задан %s (%s)\n", key, file > "/dev/stderr"
                exit 1
            }
            if (count > 1) {
                printf "version.properties: %s задан %d раза (%s)\n", key, count, file > "/dev/stderr"
                exit 1
            }
            if (result == "") {
                printf "version.properties: пустой %s (%s)\n", key, file > "/dev/stderr"
                exit 1
            }
            print result
        }
    ' "$file"
}

csqtt_version_name() {
    local root="$1" value
    value="$(csqtt_version_property "$root" versionName)" || return 1
    if [[ ! "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        printf 'version.properties: versionName должен быть X.Y.Z, получено %q\n' "$value" >&2
        return 1
    fi
    printf '%s' "$value"
}

# Gradle parses versionCode with toIntOrNull(); a value above Int.MAX_VALUE
# passes a plain decimal regex but fails configuration. Keep the shell readers
# on the same bound so bump_version.sh cannot write an unbuildable file.
CSQTT_VERSION_CODE_MAX="${CSQTT_VERSION_CODE_MAX:-2147483647}"

csqtt_validate_version_code() {
    local value="$1"
    # Android treats versionCode as a positive integer; 0 is not a valid app
    # version. A leading zero would compare 007 against aapt2's 7 and fail the
    # APK identity check. Gradle repeats this regex, so keep the two in sync.
    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
        printf 'version.properties: versionCode должен быть положительным целым без ведущих нулей, получено %q\n' "$value" >&2
        return 1
    fi
    # Same-length decimal strings compare numerically with [[ > ]]; longer
    # values are out of range without any arithmetic overflow.
    if ((${#value} > ${#CSQTT_VERSION_CODE_MAX})) ||
        { ((${#value} == ${#CSQTT_VERSION_CODE_MAX})) && [[ "$value" > "$CSQTT_VERSION_CODE_MAX" ]]; }; then
        printf 'version.properties: versionCode вне диапазона Int (0..%s), получено %q\n' \
            "$CSQTT_VERSION_CODE_MAX" "$value" >&2
        return 1
    fi
}

csqtt_version_code() {
    local root="$1" value
    value="$(csqtt_version_property "$root" versionCode)" || return 1
    csqtt_validate_version_code "$value" || return 1
    printf '%s' "$value"
}
