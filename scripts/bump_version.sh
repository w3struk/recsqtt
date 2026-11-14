#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Поднимает версию приложения во всех местах, где она продублирована, и
# восстанавливает все десять файлов при ошибке через trap (SIGKILL/power loss
# не покрываются).
#
# Источник правды — version.properties; остальные девять мест правятся следом,
# Cargo.lock обновляется настоящим `cargo update --offline`, а в конце
# запускается scripts/check_versions.sh. Любая ошибка после начала правок
# откатывает все десять файлов из резервной копии во временном каталоге.
#
#   scripts/bump_version.sh patch
#   scripts/bump_version.sh minor --root /path/repo
#   scripts/bump_version.sh 3.0.0 --code 300
#   scripts/bump_version.sh major --dry-run
#
# Список целевых файлов совпадает с набором, который проверяет
# scripts/check_versions.sh (плюс Gradle-гейт внутри него).

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=scripts/version_properties.sh
source "$SCRIPT_DIR/version_properties.sh"

TARGETS=(
    version.properties
    rust-client/Cargo.toml
    rust-client/Cargo.lock
    rust-server/Cargo.toml
    rust-server/Cargo.lock
    scripts/install.sh
    rust-server/main.rs
    rust-server/web_panel.rs
    docker/compose.yml
    docker/.env.example
)

ROOT="."
BUMP=""
CODE_ARG=""
FORCE_CODE=0
FORCE_VERSION=0
DRY_RUN=0
ALLOW_DIRTY=0
CARGO_BIN="${CARGO_BIN:-cargo}"

backup_dir=""
edits_started=0

usage() {
    cat <<'EOF'
Usage: scripts/bump_version.sh <major|minor|patch|X.Y.Z> [options]

  --root DIR     каталог репозитория (по умолчанию .)
  --code N       явный versionCode (положительное целое без ведущих нулей); по умолчанию старый+1
  --force-code      разрешить versionCode не больше текущего (только вместе с --code)
  --force-version   разрешить versionName не больше текущего (откат/равенство)
  --dry-run         показать план «было -> стало», ничего не меняя
  --allow-dirty  не отказываться из-за изменённых целевых файлов

Правит version.properties, rust-client/Cargo.{toml,lock},
rust-server/Cargo.{toml,lock}, scripts/install.sh, rust-server/main.rs,
rust-server/web_panel.rs, docker/compose.yml и docker/.env.example.
EOF
}

die() {
    printf 'bump_version: %s\n' "$*" >&2
    exit 1
}

usage_error() {
    printf 'bump_version: %s\n' "$*" >&2
    exit 2
}

warn() {
    printf 'bump_version: %s\n' "$*" >&2
}

restore_backup() {
    local rel
    for rel in "${TARGETS[@]}"; do
        if [[ -f "$backup_dir/$rel" ]]; then
            cp -p -- "$backup_dir/$rel" "$ROOT/$rel"
        fi
    done
}

cleanup() {
    local rc=$?
    trap - EXIT INT TERM
    if ((rc != 0 && edits_started == 1)) && [[ -n "$backup_dir" && -d "$backup_dir" ]]; then
        restore_backup
        warn "правки отменены, файлы восстановлены"
        # Временный каталог удаляется только после успеха: на сбое в нём
        # остаётся последняя целая копия десяти файлов.
        warn "резервная копия оставлена: $backup_dir"
    elif [[ -n "$backup_dir" && -d "$backup_dir" ]]; then
        rm -rf -- "$backup_dir"
    fi
    exit "$rc"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

while (($# > 0)); do
    case "$1" in
        --root)
            [[ $# -ge 2 ]] || usage_error "--root требует путь"
            ROOT="$2"
            shift 2
            ;;
        --code)
            [[ $# -ge 2 ]] || usage_error "--code требует значение"
            CODE_ARG="$2"
            shift 2
            ;;
        --force-code)
            FORCE_CODE=1
            shift
            ;;
        --force-version)
            FORCE_VERSION=1
            shift
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --allow-dirty)
            ALLOW_DIRTY=1
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        --)
            shift
            break
            ;;
        -*)
            usage_error "неизвестный аргумент: $1"
            ;;
        *)
            [[ -z "$BUMP" ]] || usage_error "лишний аргумент: $1"
            BUMP="$1"
            shift
            ;;
    esac
done

[[ -n "$BUMP" ]] || { usage >&2; exit 2; }
if ((FORCE_CODE == 1)) && [[ -z "$CODE_ARG" ]]; then
    usage_error "--force-code используется только вместе с --code"
fi
[[ -d "$ROOT" ]] || usage_error "корень не найден: $ROOT"
ROOT="$(cd -- "$ROOT" && pwd)"

old_name="$(csqtt_version_name "$ROOT")" || die "не удалось прочитать versionName"
old_code="$(csqtt_version_code "$ROOT")" || die "не удалось прочитать versionCode"

IFS=. read -r v_major v_minor v_patch <<<"$old_name"
v_major=$((10#$v_major))
v_minor=$((10#$v_minor))
v_patch=$((10#$v_patch))

case "$BUMP" in
    major)
        new_name="$((v_major + 1)).0.0"
        ;;
    minor)
        new_name="$v_major.$((v_minor + 1)).0"
        ;;
    patch)
        new_name="$v_major.$v_minor.$((v_patch + 1))"
        ;;
    *)
        if [[ "$BUMP" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            new_name="$BUMP"
        else
            usage_error "версия должна быть major, minor, patch или X.Y.Z: $BUMP"
        fi
        ;;
esac

# versionName не должен оставаться прежним или уменьшаться: sort -V даёт
# числовое сравнение компонентов, а равенство попадает под тот же отказ.
if ((FORCE_VERSION == 0)); then
    lower_name="$(printf '%s\n%s\n' "$old_name" "$new_name" | sort -V | head -n 1)"
    if [[ "$lower_name" == "$new_name" ]]; then
        usage_error "новая версия $new_name не больше текущей $old_name (нужен --force-version)"
    fi
fi

if [[ -n "$CODE_ARG" ]]; then
    if ! csqtt_validate_version_code "$CODE_ARG" 2>/dev/null; then
        usage_error "--code должен быть положительным целым без ведущих нулей и не больше $CSQTT_VERSION_CODE_MAX: $CODE_ARG"
    fi
    new_code=$((10#$CODE_ARG))
    if ((new_code <= 10#$old_code)) && ((FORCE_CODE == 0)); then
        usage_error "--code $new_code не больше текущего $old_code (нужен --force-code)"
    fi
else
    new_code=$((10#$old_code + 1))
fi
if ! csqtt_validate_version_code "$new_code" 2>/dev/null; then
    die "versionCode $new_code вне диапазона (1..$CSQTT_VERSION_CODE_MAX); уменьшите базу или задайте --code"
fi

# --- Та же проверка "грязных" файлов, что и в остальных скриптах ------------

if ((DRY_RUN == 0)); then
    if command -v git >/dev/null 2>&1; then
        if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
            dirty="$(git -C "$ROOT" status --porcelain -- "${TARGETS[@]}" 2>/dev/null || true)"
            if [[ -n "$dirty" ]] && ((ALLOW_DIRTY == 0)); then
                warn "целевые файлы уже изменены; используйте --allow-dirty, чтобы продолжить"
                printf '%s\n' "$dirty" >&2
                exit 1
            fi
        else
            warn "каталог не git-репозиторий, проверка изменённых файлов пропущена"
        fi
    else
        warn "git недоступен, проверка изменённых файлов пропущена"
    fi
fi

# --- Чтение текущих значений (нужно для --dry-run и для отчёта) -------------

cargo_toml_version() {
    awk '!done && /^version[[:space:]]*=[[:space:]]*"/ {
             line = $0
             sub(/^version[[:space:]]*=[[:space:]]*"/, "", line)
             sub(/".*$/, "", line)
             print line
             done = 1
         }' "$1"
}

lock_package_version() {
    awk -v want="$2" '
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
    ' "$1"
}

script_version() {
    sed -n 's/^readonly SCRIPT_VERSION="\([^"]*\)".*/\1/p' "$1" | head -n 1
}

image_script_version() {
    sed -n 's/^readonly CSQTT_DOCKER_IMAGE="csqtt:\([^"]*\)".*/\1/p' "$1" | head -n 1
}

banner_version() {
    sed -n 's/.* reCSQTT Server \([0-9][0-9.]*\).*/\1/p' "$1" | head -n 1
}

panel_version() {
    sed -n "s/.*csqtt-panel-shell-\([0-9][0-9.]*\)-pwa[0-9]*'.*/\1/p" "$1" | head -n 1
}

docker_tag() {
    sed -n -E 's/^[[:space:]]*image:[[:space:]]*(.*)$/\1/p;
               s/^[[:space:]]*CSQTT_IMAGE=(.*)$/\1/p' "$1" \
        | head -n 1 \
        | sed -E 's/[[:space:]]+$//; s/[}"'"'"']+$//' \
        | sed -E 's/.*://'
}

# --- Проверка полноты дерева до любого вывода --------------------------------

for rel in "${TARGETS[@]}"; do
    [[ -f "$ROOT/$rel" ]] || die "файл не найден: $rel"
done

# --- План для --dry-run (ничего не пишет) -----------------------------------

if ((DRY_RUN == 1)); then
    printf 'bump_version (dry-run): %s (%s) -> %s (%s)\n' \
        "$old_name" "$old_code" "$new_name" "$new_code"
    printf '  version.properties        versionName %s -> %s\n' "$old_name" "$new_name"
    printf '  version.properties        versionCode %s -> %s\n' "$old_code" "$new_code"
    printf '  rust-client/Cargo.toml    %s -> %s\n' \
        "$(cargo_toml_version "$ROOT/rust-client/Cargo.toml")" "$new_name"
    printf '  rust-client/Cargo.lock    %s -> %s\n' \
        "$(lock_package_version "$ROOT/rust-client/Cargo.lock" csqtt-client)" "$new_name"
    printf '  rust-server/Cargo.toml    %s -> %s\n' \
        "$(cargo_toml_version "$ROOT/rust-server/Cargo.toml")" "$new_name"
    printf '  rust-server/Cargo.lock    %s -> %s\n' \
        "$(lock_package_version "$ROOT/rust-server/Cargo.lock" csqtt)" "$new_name"
    printf '  scripts/install.sh        %s -> %s (SCRIPT_VERSION)\n' \
        "$(script_version "$ROOT/scripts/install.sh")" "$new_name"
    printf '  scripts/install.sh        csqtt:%s -> csqtt:%s\n' \
        "$(image_script_version "$ROOT/scripts/install.sh")" "$new_name"
    printf '  rust-server/main.rs        %s -> %s\n' \
        "$(banner_version "$ROOT/rust-server/main.rs")" "$new_name"
    printf '  rust-server/web_panel.rs   %s -> %s\n' \
        "$(panel_version "$ROOT/rust-server/web_panel.rs")" "$new_name"
    printf '  docker/compose.yml         %s -> %s\n' \
        "$(docker_tag "$ROOT/docker/compose.yml")" "$new_name"
    printf '  docker/.env.example        %s -> %s\n' \
        "$(docker_tag "$ROOT/docker/.env.example")" "$new_name"
    exit 0
fi

# --- Резервная копия всех десяти файлов до первой правки --------------------

backup_dir="$(mktemp -d)" || die "не удалось создать временный каталог"
for rel in "${TARGETS[@]}"; do
    mkdir -p "$backup_dir/$(dirname -- "$rel")"
    cp -p -- "$ROOT/$rel" "$backup_dir/$rel"
done

if ! command -v "$CARGO_BIN" >/dev/null 2>&1; then
    die "cargo не найден: $CARGO_BIN (переопределяется через CARGO_BIN)"
fi

# --- Правки ------------------------------------------------------------------

edit_version_properties() {
    local file="$1" tmp
    tmp="$(mktemp)"
    awk -v name="$new_name" -v code="$new_code" '
        {
            # Preserve a CRLF file: rewrite the stripped line, restore the CR.
            cr = ($0 ~ /\r$/) ? "\r" : ""
            line = $0
            sub(/\r$/, "", line)
        }
        line ~ /^[[:space:]]*versionName[[:space:]]*=/ {
            sub(/=.*/, "=" name cr, line)
            print line
            next
        }
        line ~ /^[[:space:]]*versionCode[[:space:]]*=/ {
            sub(/=.*/, "=" code cr, line)
            print line
            next
        }
        { print }
    ' "$file" >"$tmp"
    cat -- "$tmp" >"$file"
    rm -f -- "$tmp"
}

edit_cargo_toml() {
    local file="$1" tmp
    tmp="$(mktemp)"
    awk -v new="$new_name" '
        !done && /^version[[:space:]]*=[[:space:]]*"/ {
            line = $0
            sub(/"[^"]*"/, "\"" new "\"", line)
            print line
            done = 1
            next
        }
        { print }
    ' "$file" >"$tmp"
    cat -- "$tmp" >"$file"
    rm -f -- "$tmp"
}

edit_install_sh() {
    local file="$1"
    sed -i -E "s/^(readonly SCRIPT_VERSION=\")[^\"]*(\")/\\1${new_name}\\2/" "$file"
    sed -i -E "s/^(readonly CSQTT_DOCKER_IMAGE=\"csqtt:)[^\"]*(\")/\\1${new_name}\\2/" "$file"
    grep -Fq "readonly SCRIPT_VERSION=\"$new_name\"" "$file" \
        || die "scripts/install.sh: SCRIPT_VERSION не обновился"
    grep -Fq "readonly CSQTT_DOCKER_IMAGE=\"csqtt:$new_name\"" "$file" \
        || die "scripts/install.sh: тег csqtt не обновился"
}

edit_banner() {
    local file="$1"
    sed -i -E "s/(reCSQTT Server )[0-9]+\\.[0-9]+\\.[0-9]+/\\1${new_name}/" "$file"
    grep -Fq "reCSQTT Server $new_name" "$file" \
        || die "rust-server/main.rs: баннер не обновился"
}

edit_panel_cache() {
    local file="$1"
    sed -i -E "s/(csqtt-panel-shell-)[0-9]+\\.[0-9]+\\.[0-9]+(-pwa[0-9]+)/\\1${new_name}\\2/" "$file"
    grep -Eq "csqtt-panel-shell-${new_name}-pwa[0-9]+" "$file" \
        || die "rust-server/web_panel.rs: CACHE_NAME не обновился"
}

edit_docker_tag() {
    local file="$1"
    sed -i -E "s#(ghcr\\.io/w3struk/recsqtt:)[0-9]+\\.[0-9]+\\.[0-9]+#\\1${new_name}#" "$file"
    grep -Fq "ghcr.io/w3struk/recsqtt:$new_name" "$file" \
        || die "$file: тег образа не обновился"
    grep -Fq "ghcr.io/w3struk/recsqtt" "$file" \
        || die "$file: имя образа изменилось"
}

verify_lock() {
    local lock="$1" backup="$2" package="$3" actual expected
    actual="$(lock_package_version "$lock" "$package")"
    [[ "$actual" == "$new_name" ]] \
        || die "$lock: $package имеет версию ${actual:-<пусто>}, ожидалась $new_name"

    # Нормализованная копия резервного lock: меняется только строка version
    # целевого пакета внутри его [[package]]; остальное обязано совпасть
    # побайтно. Это ловит и переписанные source/checksum/dependencies.
    expected="$(mktemp)"
    awk -v pkg="$package" -v new="$new_name" '
        /^\[\[package\]\]/ { in_target = 0; name = "" }
        /^name = "/ {
            value = $0
            sub(/^name = "/, "", value)
            sub(/"$/, "", value)
            name = value
            in_target = (name == pkg) ? 1 : 0
        }
        in_target && !done && /^version = "/ {
            sub(/"[^"]*"/, "\"" new "\"")
            done = 1
        }
        { print }
    ' "$backup" >"$expected"

    if ! cmp -s "$lock" "$expected"; then
        diff -u --label "ожидалось" --label "получено" "$expected" "$lock" >&2 || true
        rm -f -- "$expected"
        die "$lock: отличается от ожидаемого (изменены посторонние строки)"
    fi
    rm -f -- "$expected"
}

run_cargo_update() {
    local dir="$1" package="$2"
    (cd "$ROOT/$dir" && "$CARGO_BIN" update --offline -p "$package" --precise "$new_name")
}

edits_started=1

edit_version_properties "$ROOT/version.properties"
edit_cargo_toml "$ROOT/rust-client/Cargo.toml"
edit_cargo_toml "$ROOT/rust-server/Cargo.toml"
edit_install_sh "$ROOT/scripts/install.sh"
edit_banner "$ROOT/rust-server/main.rs"
edit_panel_cache "$ROOT/rust-server/web_panel.rs"
edit_docker_tag "$ROOT/docker/compose.yml"
edit_docker_tag "$ROOT/docker/.env.example"

run_cargo_update rust-client csqtt-client
run_cargo_update rust-server csqtt

verify_lock "$ROOT/rust-client/Cargo.lock" "$backup_dir/rust-client/Cargo.lock" csqtt-client
verify_lock "$ROOT/rust-server/Cargo.lock" "$backup_dir/rust-server/Cargo.lock" csqtt

# --- Финальный гейт ----------------------------------------------------------

TARGET_CHECKER="$ROOT/scripts/check_versions.sh"
if [[ ! -f "$TARGET_CHECKER" ]]; then
    die "в целевом дереве нет scripts/check_versions.sh: $TARGET_CHECKER"
fi
if ! bash "$TARGET_CHECKER" --root "$ROOT"; then
    die "scripts/check_versions.sh не принял результат"
fi

printf 'bump_version: OK %s (%s) -> %s (%s)\n' \
    "$old_name" "$old_code" "$new_name" "$new_code"
