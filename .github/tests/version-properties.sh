#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Секция стенда для scripts/version_properties.sh — единственного shell-парсера
# version.properties. Проверяет и чтение, и отказы: молчаливое «прочиталось
# пусто» здесь опаснее ошибки, потому что из версии строятся имена APK.

set -euo pipefail
export LC_ALL=C

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$TEST_DIR/../.." && pwd)"

# shellcheck source=../../scripts/version_properties.sh
source "$REPO_ROOT/scripts/version_properties.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

ok() {
    pass=$((pass + 1))
}

expect_value() {
    local label="$1" function="$2" root="$3" expected="$4" actual
    actual="$("$function" "$root")" || fail "$label: $function завершилась ошибкой"
    [[ "$actual" == "$expected" ]] ||
        fail "$label: ожидалось '$expected', получено '$actual'"
    ok "$label"
}

expect_fail() {
    local label="$1" fragment="$2" function="$3" root="$4" out err
    err="$TMP/stderr.txt"
    : >"$err"
    if out="$("$function" "$root" 2>"$err")"; then
        fail "$label: функция завершилась успешно, а должна была упасть"
    fi
    [[ -z "$out" ]] || fail "$label: stdout должен быть пуст, получено '$out'"
    grep -qF -- "$fragment" "$err" ||
        {
            printf 'stderr was:\n' >&2
            sed 's/^/  | /' "$err" >&2
            fail "$label: stderr не содержит '$fragment'"
        }
    ok "$label"
}

mkdir -p "$TMP/valid"
printf 'versionName=1.2.3\nversionCode=42\n' >"$TMP/valid/version.properties"
expect_value "versionName из простого файла" csqtt_version_name "$TMP/valid" "1.2.3"
expect_value "versionCode из простого файла" csqtt_version_code "$TMP/valid" "42"

mkdir -p "$TMP/messy"
printf '# комментарий\r\n  versionName = 2.5.4 \r\n\nversionCode= 234\r\n' \
    >"$TMP/messy/version.properties"
expect_value "CRLF, отступы и комментарии: versionName" \
    csqtt_version_name "$TMP/messy" "2.5.4"
expect_value "CRLF, отступы и комментарии: versionCode" \
    csqtt_version_code "$TMP/messy" "234"

mkdir -p "$TMP/missing-file"
expect_fail "отсутствующий файл" "version.properties" csqtt_version_name "$TMP/missing-file"

mkdir -p "$TMP/missing-name"
printf 'versionCode=42\n' >"$TMP/missing-name/version.properties"
expect_fail "нет versionName" "не задан versionName" csqtt_version_name "$TMP/missing-name"

mkdir -p "$TMP/missing-code"
printf 'versionName=1.2.3\n' >"$TMP/missing-code/version.properties"
expect_fail "нет versionCode" "не задан versionCode" csqtt_version_code "$TMP/missing-code"

mkdir -p "$TMP/duplicate"
printf 'versionName=1.2.3\nversionName=1.2.4\nversionCode=42\n' \
    >"$TMP/duplicate/version.properties"
expect_fail "дубликат versionName" "задан 2 раза" csqtt_version_name "$TMP/duplicate"

mkdir -p "$TMP/empty-value"
printf 'versionName=\nversionCode=42\n' >"$TMP/empty-value/version.properties"
expect_fail "пустой versionName" "пустой versionName" csqtt_version_name "$TMP/empty-value"

mkdir -p "$TMP/bad-name"
printf 'versionName=1.2\nversionCode=42\n' >"$TMP/bad-name/version.properties"
expect_fail "versionName не X.Y.Z" "X.Y.Z" csqtt_version_name "$TMP/bad-name"

mkdir -p "$TMP/bad-code"
printf 'versionName=1.2.3\nversionCode=next\n' >"$TMP/bad-code/version.properties"
expect_fail "versionCode не целый" "целым" csqtt_version_code "$TMP/bad-code"

mkdir -p "$TMP/leading-zero"
printf 'versionName=1.2.3\nversionCode=007\n' >"$TMP/leading-zero/version.properties"
expect_fail "versionCode с ведущими нулями" "ведущих нулей" csqtt_version_code "$TMP/leading-zero"

mkdir -p "$TMP/zero-code"
printf 'versionName=1.2.3\nversionCode=0\n' >"$TMP/zero-code/version.properties"
expect_fail "versionCode=0" "положительным" csqtt_version_code "$TMP/zero-code"

mkdir -p "$TMP/max-code"
printf 'versionName=1.2.3\nversionCode=2147483647\n' >"$TMP/max-code/version.properties"
expect_value "versionCode на верхней границе Int" csqtt_version_code "$TMP/max-code" "2147483647"

mkdir -p "$TMP/overflow-code"
printf 'versionName=1.2.3\nversionCode=2147483648\n' >"$TMP/overflow-code/version.properties"
expect_fail "versionCode выше Int.MAX_VALUE" "диапазона Int" csqtt_version_code "$TMP/overflow-code"

mkdir -p "$TMP/huge-code"
printf 'versionName=1.2.3\nversionCode=3000000000\n' >"$TMP/huge-code/version.properties"
expect_fail "versionCode 3000000000" "диапазона Int" csqtt_version_code "$TMP/huge-code"

printf 'version-properties: %d assertions OK\n' "$pass"
