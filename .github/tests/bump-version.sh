#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Фикстура scripts/bump_version.sh: собирает минимальное дерево во временном
# каталоге, подкладывает поддельный `cargo` через CARGO_BIN и проверяет
# арифметику версий, валидацию --code, неизменность дерева при --dry-run и
# полный откат при сбое cargo или при изменении постороннего пакета в Cargo.lock.
# Рабочее дерево и сеть не затрагиваются.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
BUMP="$REPO_ROOT/scripts/bump_version.sh"
CHECKER="$REPO_ROOT/scripts/check_versions.sh"

if [[ ! -f "$BUMP" ]]; then
    echo "fixture: не найден $BUMP" >&2
    exit 1
fi
if [[ ! -f "$CHECKER" ]]; then
    echo "fixture: не найден $CHECKER" >&2
    exit 1
fi

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

# Резервные копии неудачных прогонов остаются в TMPDIR — держим их внутри
# TMP_ROOT, чтобы фикстура убирала за собой.
export TMPDIR="$TMP_ROOT/tmp"
mkdir -p "$TMPDIR"

TREE="$TMP_ROOT/tree"
FAKE_BIN="$TMP_ROOT/fakebin"
FAKE_CARGO="$FAKE_BIN/cargo"

mkdir -p "$FAKE_BIN"
cat >"$FAKE_CARGO" <<'FAKE_CARGO_EOF'
#!/usr/bin/env bash
# Поддельный cargo: правит только версию пакета из -p в ./Cargo.lock.
# FAKE_CARGO_MODE=fail    — падает, как будто cargo недоступен/сломан.
# FAKE_CARGO_MODE=move    — дополнительно двигает версию постороннего пакета.
# FAKE_CARGO_MODE=corrupt — меняет checksum постороннего пакета, версии не трогает.
set -euo pipefail

pkg=""
precise=""
while (($# > 0)); do
    case "$1" in
        -p)
            pkg="${2:-}"
            shift 2
            ;;
        --precise)
            precise="${2:-}"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

mode="${FAKE_CARGO_MODE:-update}"
if [[ "$mode" == "fail" ]]; then
    echo "fake cargo: simulated failure" >&2
    exit 1
fi
if [[ -z "$pkg" || -z "$precise" ]]; then
    echo "fake cargo: ожидались -p PKG --precise VERSION" >&2
    exit 1
fi
if [[ ! -f Cargo.lock ]]; then
    echo "fake cargo: Cargo.lock не найден в $(pwd)" >&2
    exit 1
fi

set_lock_version() {
    local package="$1" version="$2" tmp
    tmp="$(mktemp)"
    awk -v pkg="$package" -v new="$version" '
        /^\[\[package\]\]/ { name = ""; done = 0 }
        /^name = "/ {
            value = $0
            sub(/^name = "/, "", value)
            sub(/"$/, "", value)
            name = value
        }
        name == pkg && !done && /^version = "/ {
            sub(/"[^"]*"/, "\"" new "\"")
            done = 1
        }
        { print }
    ' Cargo.lock >"$tmp"
    cat -- "$tmp" >Cargo.lock
    rm -f -- "$tmp"
}

set_lock_version "$pkg" "$precise"

if [[ "$mode" == "move" ]]; then
    set_lock_version "otherdep" "9.9.9"
fi

if [[ "$mode" == "corrupt" ]]; then
    sed -i 's/^checksum = ".*"$/checksum = "corrupted"/' Cargo.lock
fi
FAKE_CARGO_EOF
chmod +x "$FAKE_CARGO"

export CARGO_BIN="$FAKE_CARGO"

fail() {
    echo "FAIL fixture: $*" >&2
    exit 1
}

write_fixture() {
    rm -rf -- "$TREE"
    mkdir -p "$TREE/app" "$TREE/rust-client" "$TREE/rust-server" \
        "$TREE/scripts" "$TREE/docker"

    cat >"$TREE/version.properties" <<'EOF'
# Single source of truth for the app version.
versionName=2.5.4
versionCode=234
EOF

    cat >"$TREE/app/build.gradle.kts" <<'EOF'
val versionPropertiesFile = rootProject.layout.projectDirectory.file("version.properties")
val baseVersionName = versionProperty("versionName")
val versionCodeFromProperties = versionProperty("versionCode")
android {
    defaultConfig {
        versionCode = versionCodeFromProperties
        versionName = baseVersionName +
            (providers.gradleProperty("csqtt.versionSuffix").orNull ?: "")
    }
}
EOF

    cat >"$TREE/rust-client/Cargo.toml" <<'EOF'
[package]
name = "csqtt-client"
version = "2.5.4"
edition = "2021"
EOF

    cat >"$TREE/rust-client/Cargo.lock" <<'EOF'
version = 4

[[package]]
name = "csqtt-client"
version = "2.5.4"
dependencies = []

[[package]]
name = "otherdep"
version = "1.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "1111111111111111111111111111111111111111111111111111111111111111"
EOF

    cat >"$TREE/rust-server/Cargo.toml" <<'EOF'
[package]
name = "csqtt"
version = "2.5.4"
edition = "2021"
EOF

    cat >"$TREE/rust-server/Cargo.lock" <<'EOF'
version = 4

[[package]]
name = "csqtt"
version = "2.5.4"
dependencies = []

[[package]]
name = "otherdep"
version = "1.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "1111111111111111111111111111111111111111111111111111111111111111"
EOF

    cat >"$TREE/scripts/install.sh" <<'EOF'
#!/bin/bash
readonly SCRIPT_VERSION="2.5.4"
readonly CSQTT_DOCKER_IMAGE="csqtt:2.5.4"
readonly CSQTT_DOCKER_CONTAINER="csqtt"
EOF

    cat >"$TREE/rust-server/main.rs" <<'EOF'
fn banner() {
    log_event(&app, "INFO", "SYSTEM", " reCSQTT Server 2.5.4");
}
EOF

    cat >"$TREE/rust-server/web_panel.rs" <<'EOF'
const PWA_SERVICE_WORKER: &str = r##"const CACHE_NAME = 'csqtt-panel-shell-2.5.4-pwa6';
"##;
EOF

    cat >"$TREE/docker/compose.yml" <<'EOF'
services:
  csqtt:
    image: ${CSQTT_IMAGE:-ghcr.io/w3struk/recsqtt:2.5.4}
    container_name: csqtt
EOF

    cat >"$TREE/docker/.env.example" <<'EOF'
# Точная версия, не latest: тег должен совпадать с бинарём в образе.
CSQTT_IMAGE=ghcr.io/w3struk/recsqtt:2.5.4
EOF

    # bump_version.sh runs the target tree's checker; give the fixture one.
    cp "$REPO_ROOT/scripts/check_versions.sh" "$TREE/scripts/check_versions.sh"
    cp "$REPO_ROOT/scripts/version_properties.sh" "$TREE/scripts/version_properties.sh"
}

snapshot() {
    (
        cd -- "$TREE"
        sha256sum \
            version.properties \
            app/build.gradle.kts \
            rust-client/Cargo.toml \
            rust-client/Cargo.lock \
            rust-server/Cargo.toml \
            rust-server/Cargo.lock \
            scripts/install.sh \
            rust-server/main.rs \
            rust-server/web_panel.rs \
            docker/compose.yml \
            docker/.env.example
    )
}

lock_version() {
    local lock="$1" package="$2"
    awk -v want="$package" '
        /^\[\[package\]\]/ { matched = 0 }
        /^name = "/ {
            matched = ($0 == "name = \"" want "\"") ? 1 : 0
        }
        matched && /^version = "/ {
            line = $0
            sub(/^version = "/, "", line)
            sub(/"$/, "", line)
            print line
            exit
        }
    ' "$lock"
}

# assert_bumped <versionName> <versionCode>
assert_bumped() {
    local name="$1" code="$2"

    grep -Fq "versionName=$name" "$TREE/version.properties" \
        || fail "version.properties: versionName не $name"
    grep -Fq "versionCode=$code" "$TREE/version.properties" \
        || fail "version.properties: versionCode не $code"

    grep -Fq "version = \"$name\"" "$TREE/rust-client/Cargo.toml" \
        || fail "rust-client/Cargo.toml не обновился"
    grep -Fq "version = \"$name\"" "$TREE/rust-server/Cargo.toml" \
        || fail "rust-server/Cargo.toml не обновился"

    [[ "$(lock_version "$TREE/rust-client/Cargo.lock" csqtt-client)" == "$name" ]] \
        || fail "rust-client/Cargo.lock: csqtt-client не $name"
    [[ "$(lock_version "$TREE/rust-server/Cargo.lock" csqtt)" == "$name" ]] \
        || fail "rust-server/Cargo.lock: csqtt не $name"

    grep -Fq "readonly SCRIPT_VERSION=\"$name\"" "$TREE/scripts/install.sh" \
        || fail "scripts/install.sh: SCRIPT_VERSION не $name"
    grep -Fq "readonly CSQTT_DOCKER_IMAGE=\"csqtt:$name\"" "$TREE/scripts/install.sh" \
        || fail "scripts/install.sh: тег csqtt не $name"

    grep -Fq "reCSQTT Server $name" "$TREE/rust-server/main.rs" \
        || fail "rust-server/main.rs: баннер не $name"
    grep -Fq "csqtt-panel-shell-$name-pwa6" "$TREE/rust-server/web_panel.rs" \
        || fail "rust-server/web_panel.rs: CACHE_NAME не $name (или потерян pwa6)"

    grep -Fq "ghcr.io/w3struk/recsqtt:$name" "$TREE/docker/compose.yml" \
        || fail "docker/compose.yml: тег не $name"
    grep -Fq "ghcr.io/w3struk/recsqtt:$name" "$TREE/docker/.env.example" \
        || fail "docker/.env.example: тег не $name"

    if [[ -n "$(find "$TREE" -name '*.bak' -print -quit)" ]]; then
        fail "в дереве появились .bak-файлы"
    fi

    bash "$CHECKER" --root "$TREE" >/dev/null \
        || fail "scripts/check_versions.sh не принял дерево после bump"
}

# expect_bump_failure <описание> <ожидаемая подстрока stderr> [env...]
expect_failure_unchanged() {
    local label="$1" before after out err
    shift
    write_fixture
    before="$(snapshot)"
    out="$TMP_ROOT/out.txt"
    err="$TMP_ROOT/err.txt"
    if "$@" >"$out" 2>"$err"; then
        cat "$err" >&2
        fail "$label: bump обязан упасть"
    fi
    after="$(snapshot)"
    if [[ "$before" != "$after" ]]; then
        fail "$label: дерево изменилось, отката не было"
    fi
}

# 1. patch.
write_fixture
bash "$BUMP" patch --root "$TREE" >/dev/null || fail "patch: bump упал"
assert_bumped "2.5.5" "235"

# 2. minor.
write_fixture
bash "$BUMP" minor --root "$TREE" >/dev/null || fail "minor: bump упал"
assert_bumped "2.6.0" "235"

# 3. major.
write_fixture
bash "$BUMP" major --root "$TREE" >/dev/null || fail "major: bump упал"
assert_bumped "3.0.0" "235"

# 4. Явный X.Y.Z.
write_fixture
bash "$BUMP" 3.2.1 --root "$TREE" >/dev/null || fail "X.Y.Z: bump упал"
assert_bumped "3.2.1" "235"

# 4a. Равенство версии отвергается без --force-version.
write_fixture
before="$(snapshot)"
if bash "$BUMP" 2.5.4 --root "$TREE" >/dev/null 2>"$TMP_ROOT/err.txt"; then
    fail "2.5.4: равенство без --force-version должно падать"
fi
[[ "$before" == "$(snapshot)" ]] || fail "2.5.4: отказ изменил дерево"
grep -Fq -- "--force-version" "$TMP_ROOT/err.txt" \
    || fail "2.5.4: сообщение не предлагает --force-version"

write_fixture
bash "$BUMP" 2.5.4 --root "$TREE" --force-version >/dev/null \
    || fail "2.5.4 --force-version: bump упал"
assert_bumped "2.5.4" "235"

# 4b. Откат версии отвергается без --force-version.
write_fixture
before="$(snapshot)"
if bash "$BUMP" 1.0.0 --root "$TREE" >/dev/null 2>"$TMP_ROOT/err.txt"; then
    fail "1.0.0: откат без --force-version должен падать"
fi
[[ "$before" == "$(snapshot)" ]] || fail "1.0.0: отказ изменил дерево"
grep -Fq -- "--force-version" "$TMP_ROOT/err.txt" \
    || fail "1.0.0: сообщение не предлагает --force-version"

write_fixture
bash "$BUMP" 1.0.0 --root "$TREE" --force-version >/dev/null \
    || fail "1.0.0 --force-version: bump упал"
assert_bumped "1.0.0" "235"

# 5. Некорректная явная версия.
write_fixture
if bash "$BUMP" 1.2 --root "$TREE" >/dev/null 2>&1; then
    fail "1.2: неполная версия должна падать"
fi
grep -Fq "versionName=2.5.4" "$TREE/version.properties" \
    || fail "1.2: дерево изменилось"

# 6. --code с ведущим нулём.
write_fixture
if bash "$BUMP" patch --root "$TREE" --code 0235 >/dev/null 2>&1; then
    fail "--code 0235: ведущий ноль должен падать"
fi
grep -Fq "versionName=2.5.4" "$TREE/version.properties" \
    || fail "--code 0235: дерево изменилось"

# 6b. --code выше Int.MAX_VALUE: Gradle отвергнет такой versionCode.
write_fixture
if bash "$BUMP" patch --root "$TREE" --code 2147483648 >/dev/null 2>&1; then
    fail "--code 2147483648: значение выше Int.MAX_VALUE должно падать"
fi
grep -Fq "versionName=2.5.4" "$TREE/version.properties" \
    || fail "--code 2147483648: дерево изменилось"
if bash "$BUMP" patch --root "$TREE" --code 3000000000 >/dev/null 2>&1; then
    fail "--code 3000000000: значение выше Int.MAX_VALUE должно падать"
fi

# 6c. --code 0: versionCode должен быть положительным.
write_fixture
if bash "$BUMP" patch --root "$TREE" --code 0 --force-code >/dev/null 2>&1; then
    fail "--code 0: versionCode должен быть положительным"
fi
grep -Fq "versionName=2.5.4" "$TREE/version.properties" \
    || fail "--code 0: дерево изменилось"

# 6d. --force-code без --code отвергается.
write_fixture
if bash "$BUMP" patch --root "$TREE" --force-code >/dev/null 2>&1; then
    fail "--force-code без --code должен падать"
fi

# 7. --code не больше текущего.
write_fixture
if bash "$BUMP" patch --root "$TREE" --code 234 >/dev/null 2>&1; then
    fail "--code 234: значение <= текущего должно падать без --force-code"
fi
if bash "$BUMP" patch --root "$TREE" --code 200 >/dev/null 2>&1; then
    fail "--code 200: значение <= текущего должно падать без --force-code"
fi

# 8. Явный --code больше текущего.
write_fixture
bash "$BUMP" patch --root "$TREE" --code 300 >/dev/null \
    || fail "--code 300: bump упал"
assert_bumped "2.5.5" "300"

# 9. --force-code разрешает неувеличение.
write_fixture
bash "$BUMP" patch --root "$TREE" --code 200 --force-code >/dev/null \
    || fail "--force-code --code 200: bump упал"
assert_bumped "2.5.5" "200"

# 9b. versionCode=Int.MAX_VALUE: +1 отсекается до правок.
write_fixture
printf '# Single source of truth for the app version.\nversionName=2.5.4\nversionCode=2147483647\n' >"$TREE/version.properties"
before="$(snapshot)"
if bash "$BUMP" patch --root "$TREE" >/dev/null 2>&1; then
    fail "versionCode=2147483647: patch обязан падать до правок"
fi
after="$(snapshot)"
[[ "$before" == "$after" ]] || fail "overflow: дерево изменилось"

# 10. --dry-run не меняет ни байта и не запускает cargo.
write_fixture
before="$(snapshot)"
bash "$BUMP" patch --root "$TREE" --dry-run >"$TMP_ROOT/dry.txt" 2>&1 \
    || fail "--dry-run упал"
after="$(snapshot)"
[[ "$before" == "$after" ]] || fail "--dry-run изменил файлы"
grep -Fq "2.5.4 -> 2.5.5" "$TMP_ROOT/dry.txt" \
    || fail "--dry-run не показал план 2.5.4 -> 2.5.5"

# 10b. --dry-run на неполном дереве обязан назвать отсутствующий файл.
write_fixture
rm -f "$TREE/rust-client/Cargo.toml"
if bash "$BUMP" patch --root "$TREE" --dry-run >"$TMP_ROOT/dry-missing.txt" 2>&1; then
    fail "--dry-run на неполном дереве должен падать"
fi
grep -Fq "rust-client/Cargo.toml" "$TMP_ROOT/dry-missing.txt" \
    || fail "--dry-run не назвал отсутствующий rust-client/Cargo.toml"

# 10c. CRLF в version.properties сохраняется после правки.
write_fixture
printf 'versionName=2.5.4\r\nversionCode=234\r\n' >"$TREE/version.properties"
bash "$BUMP" patch --root "$TREE" --code 235 >/dev/null \
    || fail "--code 235 (CRLF): bump упал"
grep -q $'versionName=2.5.5\r$' "$TREE/version.properties" \
    || fail "CRLF: versionName потерял CR"
grep -q $'versionCode=235\r$' "$TREE/version.properties" \
    || fail "CRLF: versionCode потерял CR"

# 11. Посторонний пакет в Cargo.lock двигается — обязателен откат.
write_fixture
before="$(snapshot)"
if FAKE_CARGO_MODE=move bash "$BUMP" patch --root "$TREE" \
    >"$TMP_ROOT/out.txt" 2>"$TMP_ROOT/err.txt"; then
    fail "move: bump обязан упасть"
fi
after="$(snapshot)"
[[ "$before" == "$after" ]] || fail "move: откат не восстановил дерево"
grep -Fq "отличается от ожидаемого" "$TMP_ROOT/err.txt" \
    || fail "move: нет сообщения о побайтном расхождении"

# 11a. Посторонняя строка lock (checksum) меняется без смены версии — откат.
write_fixture
before="$(snapshot)"
if FAKE_CARGO_MODE=corrupt bash "$BUMP" patch --root "$TREE" \
    >"$TMP_ROOT/out.txt" 2>"$TMP_ROOT/err.txt"; then
    fail "corrupt: bump обязан упасть"
fi
after="$(snapshot)"
[[ "$before" == "$after" ]] || fail "corrupt: откат не восстановил дерево"
grep -Fq "отличается от ожидаемого" "$TMP_ROOT/err.txt" \
    || fail "corrupt: нет сообщения о побайтном расхождении"

# 12. cargo падает — обязателен откат.
write_fixture
before="$(snapshot)"
if FAKE_CARGO_MODE=fail bash "$BUMP" patch --root "$TREE" \
    >"$TMP_ROOT/out.txt" 2>"$TMP_ROOT/err.txt"; then
    fail "cargo fail: bump обязан упасть"
fi
after="$(snapshot)"
[[ "$before" == "$after" ]] || fail "cargo fail: откат не восстановил дерево"

# 13. cargo отсутствует — обязателен откат (правок ещё нет, дерево цело).
expect_failure_unchanged "cargo отсутствует" \
    env CARGO_BIN="$TMP_ROOT/no-such-cargo" bash "$BUMP" patch --root "$TREE"

# 14. Грязный целевой файл: отказ без --allow-dirty, успех с ним.
write_fixture
(
    cd -- "$TREE"
    git init -q
    git config user.email "fixture@example.invalid"
    git config user.name "fixture"
    git add -A
    git commit -qm init
)
printf '\n# dirty\n' >>"$TREE/version.properties"
before="$(snapshot)"
if bash "$BUMP" patch --root "$TREE" >"$TMP_ROOT/out.txt" 2>"$TMP_ROOT/err.txt"; then
    fail "dirty: без --allow-dirty bump обязан отказать"
fi
[[ "$before" == "$(snapshot)" ]] || fail "dirty: отказ изменил дерево"
grep -Fq "allow-dirty" "$TMP_ROOT/err.txt" \
    || fail "dirty: сообщение не предлагает --allow-dirty"

bash "$BUMP" patch --root "$TREE" --allow-dirty >/dev/null \
    || fail "dirty + --allow-dirty: bump упал"
assert_bumped "2.5.5" "235"

# 15. --root судит целевое дерево его собственным чекером.
write_fixture
cat >"$TREE/scripts/check_versions.sh" <<'EOF'
#!/usr/bin/env bash
echo "target checker used" >&2
exit 1
EOF
chmod +x "$TREE/scripts/check_versions.sh"
if bash "$BUMP" patch --root "$TREE" >"$TMP_ROOT/out.txt" 2>"$TMP_ROOT/err.txt"; then
    fail "target checker: bump обязан упасть"
fi
grep -Fq "target checker used" "$TMP_ROOT/err.txt" \
    || fail "target checker: использован чекер вызывающего дерева"

echo "bump_version fixture: OK"
