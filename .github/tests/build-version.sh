#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Секция стенда для .github/scripts/build-version.sh.
#
# Значения версии и versionCode не хардкодятся: они читаются из
# version.properties тем же helper'ом, что и в скрипте, иначе тест закреплял бы
# конкретный номер версии вместо контракта.

set -euo pipefail
export LC_ALL=C

# В CI переменные GITHUB_OUTPUT/GITHUB_ENV заданы для каждого шага, и
# скрипты пишут туда вместо stdout. Тест проверяет именно stdout, поэтому
# CI-каналы нужно убрать — иначе локально зелёный тест падает в прогоне.
unset GITHUB_OUTPUT GITHUB_ENV GITHUB_PATH GITHUB_STEP_SUMMARY

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$TEST_DIR/../.." && pwd)"
SCRIPT="$REPO_ROOT/.github/scripts/build-version.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

ok() {
    pass=$((pass + 1))
    printf '[OK] %s\n' "$1"
}

# shellcheck source=../../scripts/version_properties.sh
source "$REPO_ROOT/scripts/version_properties.sh"
VERSION="$(csqtt_version_name "$REPO_ROOT")"
CODE="$(csqtt_version_code "$REPO_ROOT")"
HEAD_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
[[ -n "$VERSION" && -n "$CODE" && -n "$HEAD_SHA" ]] ||
    fail "не удалось прочитать исходные значения версии из репозитория"

# assert_key <output> <key> <expected> <msg>
assert_key() {
    local out="$1" key="$2" expected="$3" msg="$4" value
    value="$(printf '%s\n' "$out" | sed -n "s/^${key}=//p" | head -n 1)"
    [[ "$value" == "$expected" ]] ||
        fail "$msg: ожидалось ${key}=${expected}, получено '${value}'"
    ok "$msg"
}

# expect_fail <msg> -- <args...>
expect_fail() {
    local msg="$1"
    shift
    [[ "$1" == "--" ]] && shift
    if "$SCRIPT" "$@" >"$TMP/out" 2>"$TMP/err"; then
        fail "$msg: скрипт завершился успешно, а должен был упасть"
    fi
    ok "$msg"
}

# 1. Без переопределения: version == base, suffix пуст.
out="$("$SCRIPT" --root "$REPO_ROOT")"
assert_key "$out" base "$VERSION" "без суффикса: base"
assert_key "$out" version "$VERSION" "без суффикса: version == base"
assert_key "$out" suffix "" "без суффикса: suffix пуст"
assert_key "$out" version-code "$CODE" "без суффикса: version-code"
assert_key "$out" commit "$HEAD_SHA" "без суффикса: commit"

# 2. Суффикс беты доезжает целиком.
BETA="${VERSION}-beta.abc1234"
out="$("$SCRIPT" --root "$REPO_ROOT" --version-name "$BETA")"
assert_key "$out" version "$BETA" "с суффиксом: version"
assert_key "$out" suffix "-beta.abc1234" "с суффиксом: suffix"
assert_key "$out" base "$VERSION" "с суффиксом: base не меняется"

# 3. --root указывает на другое дерево: версия берётся оттуда, а не из cwd.
ALT_ROOT="$TMP/alt-root"
mkdir -p "$ALT_ROOT"
printf 'versionName=7.7.7\nversionCode=777\n' >"$ALT_ROOT/version.properties"
git -c init.defaultBranch=main init -q "$ALT_ROOT"
git -C "$ALT_ROOT" -c user.email=test@example.com -c user.name=test add version.properties
git -C "$ALT_ROOT" -c user.email=test@example.com -c user.name=test commit -qm init
out="$("$SCRIPT" --root "$ALT_ROOT")"
assert_key "$out" base "7.7.7" "--root: base из другого дерева"
assert_key "$out" version-code "777" "--root: version-code из другого дерева"

# 4–8. Отказы.
# Пустые значения = «читать из файла»: так release.yml/build.yml передают
# отсутствующие override'ы; пустая строка не должна падать как «нет значения».
out="$("$SCRIPT" --root "$REPO_ROOT" --version-name "" --version-code "" --expected-commit "")"
assert_key "$out" version "$VERSION" "пустые override'ы: version из файла"
assert_key "$out" version-code "$CODE" "пустые override'ы: version-code из файла"

expect_fail "чужой префикс отвергнут" -- --root "$REPO_ROOT" --version-name "2.6.0-x"
expect_fail "суффикс без дефиса отвергнут" -- --root "$REPO_ROOT" --version-name "${VERSION}0"
expect_fail "чужой versionCode отвергнут" -- --root "$REPO_ROOT" --version-code "999999"
expect_fail "чужой commit отвергнут" -- --root "$REPO_ROOT" \
    --expected-commit "0000000000000000000000000000000000000000"
mkdir -p "$TMP/empty"
expect_fail "каталог без version.properties отвергнут" -- --root "$TMP/empty"

# 8. GITHUB_OUTPUT: ровно пять строк key=value, stdout пуст.
GITHUB_OUTPUT="$TMP/github-output" "$SCRIPT" --root "$REPO_ROOT" >"$TMP/stdout"
[[ -s "$TMP/stdout" ]] && fail "при заданном GITHUB_OUTPUT в stdout что-то попало"
mapfile -t lines <"$TMP/github-output"
[[ "${#lines[@]}" -eq 5 ]] ||
    fail "в GITHUB_OUTPUT ожидалось 5 строк, получено ${#lines[@]}"
for line in "${lines[@]}"; do
    [[ "$line" =~ ^(base|version|suffix|version-code|commit)= ]] ||
        fail "в GITHUB_OUTPUT неожиданная строка: '$line'"
done
ok "GITHUB_OUTPUT: пять строк key=value, stdout пуст"

printf 'build-version: %d assertions OK (version %s, code %s)\n' "$pass" "$VERSION" "$CODE"
