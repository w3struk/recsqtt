# Сборка reCSQTT (Linux)

Проект собирается на Linux (включая WSL2). Нативная часть делится на две
независимые ветки, которые связываются через `app/build/generated`:

```
rust-client/build_so.sh   ->  app/build/generated/jniLibs/<variant>/<abi>/libclient.so
                |
        scripts/build_apk.sh  ->  ./gradlew :app:assembleRelease  ->  app/release/recsqtt-*.apk

rust-server/build_linux.sh ->  app/build/generated/assets/recsqtt-linux-amd64
                            ->  rust-server/dist/recsqtt-linux-amd64
```

Gradle собирает и проверяет **только клиент** (`cargo-ndk`): APK не содержит
серверного бинарника. Сервер (`cargo-zigbuild`, статический musl, только amd64)
собирается отдельным скриптом локально или в CI и публикуется release-ассетом;
чистый клон собирает APK без ручных шагов.

В `app/src` ничего не попадает, а `./gradlew clean` удаляет нативную часть
целиком (пересборка cargo инкрементальна).

## Зависимости

| Компонент | Версия / примечание |
| --- | --- |
| JDK | 17+ |
| Android SDK | `compileSdk 37`; `platforms;android-37.0` и `build-tools;37.0.0` — из `scripts/toolchain.env` |
| Android NDK | `27.0.12077973` — из `scripts/toolchain.env`; ставится в `$ANDROID_HOME/ndk/<версия>` |
| Rust | `1.97.1` — `RUST_TOOLCHAIN` из `scripts/toolchain.env`, продублирован в `rust-toolchain.toml` обоих crate'ов; Android-таргеты клиента rustup доустановит сам |
| cargo-ndk | `4.1.2` — `CARGO_NDK_VERSION` из `scripts/toolchain.env` |
| Zig | `0.17.0` — `ZIG_VERSION` из `scripts/toolchain.env` (`zig` в `PATH`) |
| cargo-zigbuild | `0.24.0` — `CARGO_ZIGBUILD_VERSION` из `scripts/toolchain.env` |
| CMake + Ninja | из Android SDK или системные |
| jq | нужен provenance-скриптам |
| python3 | нужен проверке файла пресета `rust-server/ru_apps_preset.json` в `rust-server/build_linux.sh` (выполняется и при `--no-tests`, локально и в CI) |
| bash, coreutils, findutils | стандартные |

### Единственный источник пинов

Версии тулчейна живут в одном shell-sourceable файле — `scripts/toolchain.env`
(`RUST_TOOLCHAIN`, `ZIG_VERSION`, `CARGO_NDK_VERSION`, `CARGO_ZIGBUILD_VERSION`,
`ANDROID_NDK_VERSION`, `ANDROID_BUILD_TOOLS_VERSION`, `ANDROID_PLATFORM`).
Файл только source'ится и экспортирует переменные; литералов версий в
исполняемом коде скриптов и workflow быть не должно — в комментариях они
допустимы, а workflow-тест игнорирует `#`-строки. Его читают `scripts/android_env.sh`,
`scripts/check_toolchain.sh`, `scripts/native_client_provenance.sh`,
`scripts/server_asset_provenance.sh`, локальные build-скрипты и CI — шаг
`Resolve toolchain pins` в `build.yml` кладёт те же значения в `$GITHUB_ENV`.

`scripts/check_toolchain.sh --root .` сверяет `channel` в обоих
`rust-toolchain.toml` и `rust-version` в обоих `Cargo.toml` с `RUST_TOOLCHAIN` и
входит в гейт `Gate: version consistency`. Смена пина — правка одного файла плюс
`bash scripts/check_toolchain.sh --root .`.

NDK ищется строго по пину: `scripts/android_env.sh` берёт
`$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION`. Если его нет, а установлен другой NDK,
сборка падает с подсказкой. Осознанный opt-in на самый новый установленный NDK —
`ALLOW_NEWEST_NDK=1`; тогда helper печатает предупреждение о расхождении с пином.

`scripts/android_env.sh` сам находит SDK/NDK: `ANDROID_HOME` → `ANDROID_SDK_ROOT` →
`sdk.dir` из `local.properties` → типовые пути (`~/Android/Sdk`, `~/.android-sdk`,
`~/android-sdk`, `/opt/android-sdk`, `/usr/lib/android-sdk`). NDK:
`ANDROID_NDK_HOME` → `ANDROID_NDK_ROOT` → `NDK_HOME` →
самый новый `$SDK/ndk/*`. При необходимости задайте явно:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
source scripts/toolchain.env
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
```

`local.properties` — локальный файл, он в `.gitignore` и не коммитится. Android
Studio сама создаёт в нём `sdk.dir` — это штатный способ задать SDK для локальной
машины. На CI такого файла нет, поэтому там путь задаётся через
`ANDROID_HOME`/`ANDROID_SDK_ROOT`.

## Быстрый старт

```bash
# 1. Сервер (статический musl, только amd64)
bash rust-server/build_linux.sh --no-tests

# 2. Клиентская нативная часть (3 ABI)
bash rust-client/build_so.sh --no-tests

# 3. Cервер и клиент параллельно:
bash scripts/build_both_parallel.sh

# 4. APK (release; нужен настроенный keystore — см. ниже)
bash scripts/build_apk.sh
```

Без флагов `--tests/--no-tests` скрипты запускают проверки, если stdin не TTY
(в CI), и спрашивают при интерактивном запуске.

Серверный `cargo` пишет не в `rust-server/target`, а в общий каталог
`build/csqtt-uring-linux-recsqtt-linux-amd64`: его задаёт `[build] target-dir` в
`rust-server/.cargo/config.toml` — тот же каталог, что используют `build_linux.sh`,
`build_linux_variants.sh` и CI. Путь относительный и резолвится от каталога конфига,
поэтому конфиг действует только при `cd rust-server`; `cargo` из корня репозитория
его не увидит и создаст локальный `./target`. Переменная `CARGO_TARGET_DIR`
перекрывает значение из конфига (так поступает `build_linux.sh`), а `cargo clean`
из `rust-server` чистит этот общий каталог целиком, а не отдельный `target/`.

### Gradle

```bash
./gradlew :app:assembleDebug            # gradlew уже executable; сервер не собирает
./gradlew :app:assembleDebug -Pcsqtt.skipNativeVerification=true  # UI-only
./gradlew :app:assembleRelease          # то же, что делает build_apk.sh
./gradlew :app:verifyNativeArtifacts    # проверить клиентские payload'ы обоих вариантов
./gradlew :app:test                     # юнит-тесты, натив не собирается

# Установка на подключённое устройство (нужен adb):
./gradlew :app:installCsqttDebug        # ABI устройства определяется автоматически
./gradlew :app:installCsqttRelease      # release: требует настроенной подписи
```

Задачи `installCsqtt*` — единственная точка входа для установки APK на устройство
(сервер ставится `scripts/install.sh`). ABI подключённого
устройства определяет сам Gradle **на этапе конфигурации**: `scripts/device_abi.sh`
печатает `abi=<abi>` и `serial=<serial>`, Gradle читает это через `providers.exec`,
а в начале сборки появляется строка `Device: 22b88e0d (arm64-v8a)`. ABI нужно
знать до конфигурации, потому что он определяет и нативную сборку (один
`cargo ndk` на ABI), и набор упаковываемых сплитов.

- **Приоритет выбора ABI:** явный `-Pcsqtt.abis` → `android.injected.build.abi`
  (его подставляет Android Studio для выбранного устройства) → ABI подключённого
  устройства → все три (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- **Автоопределение касается только install-задач** (имя задачи содержит
  `installCsqtt`). `assemble*` — в том числе `:app:assembleRelease` из
  `scripts/build_apk.sh` — по-прежнему собирают все три ABI и universal-APK.
- **Нет устройства или ABI не поддерживается** — сборка падает сразу на этапе
  конфигурации с сообщением «No device found for the install task…», а не после
  десяти минут компиляции.
- **Configuration cache.** Результат опроса устройства попадает в запись: если
  устройство сменилось после сохранения записи, установка упадёт с «no APK for …
  matches this device». Лечится `-Pcsqtt.abis=<abi>` (сбрасывает запись) или
  `--no-configuration-cache`.

Сузить сборку до одного ABI можно и вручную:

```bash
./gradlew :app:assembleDebug -Pcsqtt.abis=arm64-v8a
```

При одном ABI `isUniversalApk` выключается, universal-APK не создаётся, cargo
запускается один раз.

Установка идёт через `scripts/install_apk.sh`: он выбирает APK нужного ABI,
копирует его на устройство и ставит через `pm install` (не `adb install` —
причина в шапке того скрипта). Debug и release могут стоят одновременно — у
debug другой `applicationId` (`…debug`).

Передача APK идёт через `adb push -Z` (sync-протокол без сжатия) — самый быстрый
путь, который есть у adb, а shell-транспорт и `adb push` без `-Z` остались запасными (в
том числе для adb, не знающего `-Z`). Результат переноса — файл на устройстве, а не код возврата команды. Принудительный выбор —
`PUSH_METHOD=shell|push|auto` (по умолчанию `auto`).

### Флаги

Скрипты можно запускать для отдельной пересборки нативной части или проверки.

```bash
bash rust-server/build_linux.sh [--tests|--no-tests] [--diagnostics] [--jobs=N] [--sequential]
bash rust-client/build_so.sh     [--tests|--no-tests] [--abis=arm64-v8a,armeabi-v7a,x86_64] [--jobs=N]
                                [--variant=debug|release] [--output-dir=DIR]
bash scripts/build_apk.sh        [--verify-native-only]
bash scripts/run_system_checks.sh   # требует root для TUN/systemd проверок
```

Если `app/build/generated/assets/recsqtt-linux-amd64` отсутствует,
`run_system_checks.sh` собирает сервер сам: `rust-server/build_linux.sh --no-tests`.

- `--tests` — `cargo fmt --check`, `cargo clippy -D warnings`, `cargo test`
  (для клиента дополнительно запрет `unwrap/expect/panic` в рантайме).
  У сервера `clippy`/`test`/`build` идут через `cargo-zigbuild`. Тот сам ставит
  `cc-rs` musl C/C++ (`snmalloc-rs`, SQLite в `rusqlite`, `aws-lc-sys`) и
  CMake-тулчейн для своего child-процесса, поэтому `--tests` больше не требует
  внешнего musl-компилятора и ручных обёрток. Тулчейн передаётся через
  `RUSTUP_TOOLCHAIN`: форма `+1.97.1` в argv `cargo-zigbuild` теряет
  zig-окружение, `cargo` уходит в обычную сборку и `cc-rs` падает на
  `x86_64-linux-musl-gcc` (либо получается glibc-бинарь вместо musl).
  `scripts/zig_env.sh` — единственный источник списка ambient-переменных
  (`CC_*`, `CXX_*`, `AR_*`, `RANLIB_*`, `CARGO_TARGET_*_LINKER`,
  `CMAKE_TOOLCHAIN_FILE*`), которые снимаются перед каждым вызовом:
  `cargo-zigbuild` не перекрывает уже экспортированные переменные
  (`add_env_if_missing`). Список закрытый: `CFLAGS`, `LDFLAGS`, `CPATH`,
  `PKG_CONFIG_*`, `BINDGEN_EXTRA_CLANG_ARGS` не снимаются — это документированные
  ручки `cargo-zigbuild` для системных заголовков и sysroot; фикстуры проверяют
  и снятие списка, и pass-through `CFLAGS`. Тот же файл сверяет пины `zig` и
  `cargo-zigbuild` с `scripts/toolchain.env` и печатает точную команду фикса при
  расхождении.
  `cargo-zigbuild test` исполняет тест-бинарь напрямую, поэтому amd64-инвариант —
  не только про payload: для второй архитектуры понадобится runner (например,
  `CARGO_TARGET_<TRIPLE>_RUNNER=qemu-aarch64`) или compile-only проверки без запуска.
- `--jobs=N` у клиента — параллельные cargo-задачи на ABI; у сервера — сколько
  вариантов собирать одновременно. Серверные `--jobs=N` и `--sequential` сохранены,
  но штатный серверный вариант теперь один — amd64.
- `--diagnostics` — серверная фича `diagnostics`.
- `--variant` выбирает вариант; соответствие профилям Cargo и раздельные каталоги
  вариантов описаны в разделе [Профили Cargo и размер клиента](#профили-cargo-и-размер-клиента).

## Что получается

- `app/build/generated/jniLibs/<variant>/<abi>/libclient.so` — Rust-клиент,
  исполняемый файл (в APK пакуется с `useLegacyPackaging = true`, чтобы его можно
  было запускать, и с `keepDebugSymbols`, чтобы AGP не вырезал `.symtab`).
- `app/build/generated/assets/recsqtt-linux-amd64` — серверный бинарник для VPS;
  его собирает `rust-server/build_linux.sh` (не Gradle). Release CI публикует
  его отдельным ассетом, а `scripts/install.sh` скачивает на сервер. Та же
  release-сборка для `x86_64-unknown-linux-musl` копируется в
  `rust-server/dist/recsqtt-linux-amd64`. В APK сервер не пакуется.
- `app/release/recsqtt-<version>-{universal,arm64-v8a,armeabi-v7a,x86_64}.apk`.
- `app/build/outputs/apk/<variant>/app-<version>-<abi>-<variant>.apk` — единственный APK при
  установке на устройство: сборка сужается до ABI устройства, universal-APK не
  создаётся.

Имя APK всегда содержит версию приложения (`versionName` из `version.properties`):
`app-2.5.4-arm64-v8a-release.apk`, `recsqtt-2.5.4-universal.apk`. Файл читают Gradle
(через `providers.fileContents`, чтобы configuration cache инвалидировался при его
изменении) и общий `scripts/version_properties.sh` для скриптов `install_apk.sh` и
`build_apk.sh`, поэтому второго источника правды нет; литерал в `defaultConfig` или
отказ Gradle читать ключи гейт `check_versions.sh` не пропустит.

Ограничение amd64 относится только к VPS, а не к телефону: Android-клиент
по-прежнему поддерживает `arm64-v8a`, `armeabi-v7a` и `x86_64`. Установщик
проверяет архитектуру до переключения runtime и в systemd-, и в Docker-режиме;
приложение сервер не скачивает и не запускает. ARM-VPS больше не входят в
штатную поддержку сборки, установки, обновления и управления, включая статус и
удаление. Опциональной ARM-сборки и специальных путей совместимости нет.

## Профили Cargo и размер клиента

| Вариант Android | Профиль Cargo | Что делает |
| --- | --- | --- |
| `debug` | `android-debug` | `inherits = "dev"`: `opt-level = 1`, `strip = "debuginfo"` |
| `release` | `release` | `opt-level = 3`, `lto`, `codegen-units = 1`, `strip = true` |

Хостовый `dev` (`cargo test`, `cargo run`) остаётся с `debug = "line-tables-only"`,
в APK он не попадает.

## Логи и варианты сборки

В приложении два независимых канала, и build type влияет на них по-разному:

| Канал | Куда идёт | Debug | Release |
| --- | --- | --- | --- |
| Kotlin `Log.*` (68 вызовов) | `adb logcat`, теги по компонентам (`TunVpnService`, `TunnelService`, …) | всё видно | `Log.d`/`Log.v` вырезаются R8 (`-assumenosideeffects`), `i/w/e` остаются |
| Rust-клиент, stdout | `CSQTT/Protocol` | зеркалируется в logcat | не зеркалируется |
| Rust-клиент, stderr | `CSQTT/Diagnostic` | зеркалируется в logcat | не зеркалируется |
| In-app журнал (`TunnelManager.logs`) | экран приложения, копирование в отчёт | работает | работает; управляется тумблером `participant_show_logs` («Показывать логи»): выключен — только ошибки и статус, вкладка скрыта |

Потоки ребёнка разведены (`redirectErrorStream(false)`): stdout несёт протокол и
события `__CSQTT_EVENT__`, stderr — всё, что Rust пишет через `log_error!`/`log_info!`
(196 вызовов). Зеркалирование в logcat (`mirrorRustLineToLogcat`) включено
только под `BuildConfig.DEBUG`, поэтому release не получает поток Rust вообще.
Строки длиннее 4000 символов режутся на части — logcat обрезает длинные записи.
Severity — эвристика по содержимому (`Ошибка`/`error`/`FAIL`/`FATAL`/`panic` → `Log.e`),
потому что Rust использует `log_error!` и для информационных строк.

Отладка на устройстве:

```bash
adb logcat -s CSQTT/Protocol,CSQTT/Diagnostic
```

Журнал в приложении и logcat не дублируют друг друга: на пути журнала нет `Log.*`
(кроме debug-only зеркалирования выше), а 68 Kotlin-логов в UI не показываются.

## Provenance и защита от «протухших» бинарников

Нативные бинарники и манифесты не коммитятся, поэтому чистый клон их не содержит.
В `app/build.gradle.kts` собирается и проверяется только клиент:

- `buildNativeClientDebug` / `buildNativeClientRelease` → `bash rust-client/build_so.sh --variant=…`
- `verifyNativeClientDebug` / `verifyNativeClientRelease` — проверки
- `verifyNativeArtifacts` — ручной агрегат (проверить оба варианта клиента без упаковки)

Привязка к варианту, а не к `preBuild`:

```
mergeDebugJniLibFolders   → verifyNativeClientDebug   → buildNativeClientDebug
mergeReleaseJniLibFolders → verifyNativeClientRelease → buildNativeClientRelease
```

`preBuild` общий для всех вариантов и для юнит-тестов, поэтому гейт висит на merge-задачах:
`./gradlew :app:testDebugUnitTest` нативную часть не собирает (проверено `--dry-run`).

Каждая задача сборки объявляет `inputs`/`outputs`, поэтому Cargo не запускается, если
исходники не менялись. `outputs.upToDateWhen` дополнительно следит за
промежуточными артефактами cargo в `build/rust-client-android-*` — если их
удалили, сборка пересобирается, а не считается актуальной. Проверки выполняются
всегда: если сборка выключена (`-Pcsqtt.buildNativeClient=false`), устаревший
или отсутствующий бинарник всё равно валят сборку.

Список входов нативной сборки живёт в одном файле — `rust-client/native-inputs.txt`.
Его читают оба потребителя: Gradle (`clientNativeInputs`, inputs задачи
`buildNativeClient<Variant>`) и `scripts/native_client_provenance.sh`
(`canonical_build_input_hash`). Правка списка меняет и Gradle-входы, и хеш, поэтому
разойтись они не могут. Запись без `**` обязана что-то находить — опечатка или
удалённый файл падают с внятной ошибкой, а не выпадают из хеша молча. Каталоги
`target/` в хеш не попадают.

`buildNativeClient<Variant>` объявляет **per-ABI outputs** (`jniLibs/<variant>/<abi>/libclient.so`)
вместо `outputs.dir` каталога: Gradle знает, какие файлы принадлежат задаче. Перед
сборкой задача `cleanStaleNativeLibs<Variant>` удаляет каталоги ABI, которых нет в
`-Pcsqtt.abis`, поэтому сужение набора не оставляет старые `.so` в APK. Со стороны
provenance та же инварианта проверяется count-check'ом: набор `libclient.so` в
`jniLibs` обязан совпадать с запрошенными ABI (симметрично серверному манифесту).

Серверный provenance живёт вне Gradle: `rust-server/build_linux.sh` сам пишет
`app/build/generated/provenance/csqtt-server.json` и проверяет его, CI
дополнительно вызывает `scripts/server_asset_provenance.sh verify` перед
публикацией. Манифест содержит ровно одну запись — `recsqtt-linux-amd64`; прежний
трёхартефактный манифест отвергается. Формат манифеста и проверки целостности,
ELF, wire-revision и cargo features не меняются.

Оба манифеста пишут информационный блок `toolchain` (клиент —
`csqtt.native-client-provenance.v7`, сервер — `csqtt.server-asset-provenance.v5`):
`rustc`, `cargoNdk`, `ndk` и `source` — значения, снятые на машине сборки.
Запись никогда не гейтит сборку: на машине без `rustc`/`cargo-ndk`/NDK поля
остаются `unknown`, а проверка печатает предупреждение. Сравнение с
`scripts/toolchain.env` включается явно — флагом `--strict-toolchain` или
`CSQTT_STRICT_TOOLCHAIN=1`: тогда расхождение записанного и пина, а также
отсутствующий блок или пин становятся жёсткой ошибкой. Строгий режим
предназначен для CI/релиза; локальная проверка по умолчанию остаётся
информационной.

Какие ABI собираются: `-Pcsqtt.abis` → `android.injected.build.abi` (его подставляет
Android Studio для выбранного устройства) → ABI подключённого устройства → все три.
Поэтому запуск ▶ из IDE линкует один ABI вместо трёх, install-задача ставит ABI
устройства, а обычная сборка из консоли остаётся полной. Параметр `--abis`
у `rust-client/build_so.sh` задаёт ABI для манифеста; Gradle проверяет, что **каждый
ABI из `-Pcsqtt.abis` присутствует в манифесте** (лишние ABI допустимы). Поэтому
`-Pcsqtt.abis=arm64-v8a` работает и при полном манифесте; `scripts/build_apk.sh`
всегда требует все три ABI.

ABI подключённого устройства Gradle узнаёт сам — через `scripts/device_abi.sh`,
который он вызывает на этапе конфигурации для задач `installCsqtt*`: скрипт читает
`ro.product.cpu.abilist` через adb и берёт первый ABI из манифеста, который
устройство поддерживает (`arm64-v8a` → `armeabi-v7a` → `x86_64`).

Единственный источник ABI-списка — `rust-client/abis.txt`. Из него выводят
значения `app/build.gradle.kts` (`supportedAbis`, split-суффиксы и cargo-таргеты),
`scripts/device_abi.sh`, `rust-client/build_so.sh`,
`rust-client/build_android_abis.sh` и `scripts/native_client_provenance.sh`.
`scripts/check_abi_list.sh --root .` (входит в `Gate: version consistency`)
требует, чтобы манифест упоминали все Tier-1 потребители и чтобы **каждый** ABI
из манифеста присутствовал в каждой per-ABI карте: cargo-таргет, суффикс
target-dir, ELF-класс/машина, `valid_abi()` и `install_lib`. Поэтому добавление строки в
`abis.txt` — ещё не поддержка: без правки всех карт гейт красный. Tier-2
упаковщики (`scripts/build_apk.sh`, `.github/scripts/collect-release-assets.sh`,
входы `abis:` workflow) могут добавлять только `universal`. Манифест входит в
`rust-client/native-inputs.txt`, поэтому его правка инвалидирует Gradle-входы и
provenance-хеш.

### Память и параллелизм

Ни heap Gradle, ни число cargo-задач не заданы абсолютным числом: обе величины
машинно-зависимы, а проект собирают и на 16-гигабайтном CI-раннере, и на
8-гигабайтной WSL-виртуалке.

- `org.gradle.jvmargs` задаёт `-XX:MaxRAMPercentage=50`, а не `-Xmx`: 8 ГБ на
  раннере, 4 ГБ в 8-гигабайтной WSL. Kotlin-демону нужен явный `-Xmx2g`: его
  лаунчер подставляет свой `-Xmx` (≈85 % heap'а Gradle) и тот перебивает
  `-XX:MaxRAMPercentage`, поэтому процент там не работает.
- `~/.gradle/gradle.properties` перекрывает проектный файл (в Gradle 9 user-home
  выигрывает), поэтому собственный `org.gradle.jvmargs` там держать не нужно: иначе
  локальная и CI-сборка идут в разных режимах памяти, и ни один из них не проверяется.
- `--jobs` по умолчанию считает `rust-client/build_android_abis.sh`:
  `max(1, min(nproc, RAM GiB) / число ABI)`. ABI собираются параллельно, поэтому
  потолок по памяти важнее потолка по ядрам: на 12-ядерной/8-гигабайтной машине это
  2 задачи на ABI (6 rustc вместо 12), на 4-ядерном раннере с одним ABI — 4.
  Перекрывается `-Pcsqtt.cargoJobs=N` (Gradle) или `--jobs=N` (`build_so.sh`).

| Свойство | Смысл |
| --- | --- |
| `-Pcsqtt.abis=arm64-v8a,x86_64` | какие ABI собирать и упаковывать (перекрывает ABI, инжектированный IDE) |
| `-Pcsqtt.cargoJobs=N` | параллельные cargo-задачи на ABI (перекрывает расчёт по ядрам и памяти) |
| `-Pcsqtt.nativeTests=true` | прогнать fmt/clippy/test перед нативной сборкой |
| `-Pcsqtt.buildNativeClient=false` | не пересобирать клиент, только проверить |
| `-Pcsqtt.skipNativeVerification=true` | пропустить и сборку, и проверку клиента |

Клиент (Gradle) проверяет:

- наличие и размер каждого бинарника, ELF-класс/машина, `ET_DYN`, отсутствие
  Go-метаданных, наличие маркера транспорта;
- SHA-256 артефактов против `app/build/generated/provenance/libclient.<variant>.json`
  (манифест также фиксирует вариант и профиль cargo);
- совпадение установленного бинарника с промежуточным выводом cargo;
- `Cargo.toml`/`Cargo.lock` и **хэш всех входов сборки** (скрипты, `*.rs`,
  `shared/*.rs`, vendored-крейты).

Сервер (`rust-server/build_linux.sh`, затем CI) проверяет ELF, wire-revision,
SHA-256 против `csqtt-server.json` и хэш входов, включая `rust-server/data/*.txt`.

Манифесты машинно-зависимы (`generatedAtUtc`, SHA-256 артефактов), поэтому они лежат
в `build/` и не коммитятся: `buildInputSha256` пинит исходники, но привязан к той
машине, которая собрала бинарник. Сталое состояние ловит up-to-date-проверка Gradle
и сам `verify`; удалённый `build/` лечится пересборкой (см. `outputs.upToDateWhen`
выше) либо ручным `bash rust-client/build_so.sh --no-tests`.

## Release-подпись

`verifyReleaseSigning` валит release-задачи, если не заданы все четыре значения:
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Источники по
приоритету: **Gradle property → переменная окружения → `local.properties`**.

`local.properties` подходит для локальных `KEYSTORE_*` (файл не коммитится); на
CI те же значения прокидывайте переменными окружения или `-P`-свойствами.

```bash
KEYSTORE_FILE="$PWD/release.keystore" \
KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... \
bash scripts/build_apk.sh
```



## Бамп версии

`versionName`/`versionCode` живут в `version.properties` — единственном источнике
для Gradle и скриптов (shell читает файл через `scripts/version_properties.sh`).
Значения продублированы в десяти местах: `rust-client/Cargo.toml`/`Cargo.lock`,
`rust-server/Cargo.toml`/`Cargo.lock`, `scripts/install.sh` (`SCRIPT_VERSION`,
`CSQTT_DOCKER_IMAGE`), `rust-server/main.rs` (баннер), `rust-server/web_panel.rs`
(`CACHE_NAME` PWA) и `docker/compose.yml` + `docker/.env.example` (тег образа).
`app/build.gradle.kts` версию не хранит — он читает `version.properties`.

`scripts/bump_version.sh` правит все десять файлов и в конце запускает
проверку; при ошибке он восстанавливает все десять файлов через trap.
SIGKILL и power loss не покрываются — резервная копия при этом остаётся в
временном каталоге, но автоматического отката уже не будет:

```bash
scripts/bump_version.sh patch          # 2.5.4 -> 2.5.5, versionCode +1
scripts/bump_version.sh minor --dry-run
scripts/bump_version.sh 3.0.0 --code 300
```

`--force-version` разрешает versionName не больше текущего (равенство или откат);
без него такой бамп отвергается.

`Cargo.lock` обновляется настоящим `cargo update --offline -p <crate> --precise`,
поэтому посторонние пакеты в lock-файле не меняются. После бампа (и вручную)
должны быть зелены три гейта — они же идут последовательно в
`Gate: version consistency`:

```bash
bash scripts/check_versions.sh --root .
bash scripts/check_toolchain.sh --root .
bash scripts/check_abi_list.sh --root .
```

## Релизный CI (GitHub Actions)

CI — три workflow: `.github/workflows/build.yml` (`workflow_call`: гейты и сборка, общие для
обоих каналов), `.github/workflows/release.yml` (тег `v*` → GitHub Release) и
`.github/workflows/beta.yml` (пуш в `main` → сообщение с arm64-APK в Telegram-канал;
коммит, на котором стоит тег `v*`, бета пропускает — релизный APK публикует release.yml).
Порядок выпуска: сначала тег, потом push `main`.

Шаг `Build server binary` в `build.yml` собирает серверный бинарник
(`bash rust-server/build_linux.sh --tests`) и публикует его отдельным ассетом.
Gradle сервер не собирает: APK содержит только клиент.

Отдельного серверного гейта больше нет: `--tests` выполняется в том же шаге, что
и компиляция (`fmt`/`clippy`/`test` до `cargo-zigbuild build`), поэтому серверные
проверки и сборка делят **один** `CARGO_TARGET_DIR` —
`build/csqtt-uring-linux-recsqtt-linux-amd64` (тот же каталог выбирает локальный
`rust-server/build_linux.sh` и `rust-server/.cargo/config.toml`): release-зависимости,
собранные `cargo-zigbuild clippy --release`, переиспользуются финальным
`cargo-zigbuild build`, а debug-тесты живут в том же каталоге отдельным профилем.
Голый `cargo test` из `rust-server` в этот каталог не попадает: без
`cargo-zigbuild` C-зависимостям не хватает musl C/C++ и сборка падает на
`cc-rs: x86_64-linux-musl-gcc`. Раньше каталогов было три (`-clippy`,
`linux-musl-tests`, сборочный), и зависимости компилировались заново.

Шаг стоит до `Restore keystore`: серверной сборке подпись не нужна, а падать она
должна раньше, чем тратится время на APK. `clippy`, `test` и `build` идут через
`cargo-zigbuild` и один wrapper-кеш (`~/.cache/cargo-zigbuild/<version>/`),
поэтому переключение между `clippy --release` и финальным `build` не сбрасывает
`cc-rs`-фингерпринты C-зависимостей (`snmalloc-rs`, SQLite в `rusqlite`,
`aws-lc-sys`). Ambient-переменные снимает `scripts/zig_env.sh`, а провенанс
пишется и проверяется со `--strict-toolchain`.

CI кеширует тяжёлые каталоги, которых раньше не было ни в одном кеше:
`build/rust-client-android-*` (cargo-ndk по ABI), `build/csqtt-uring-linux-recsqtt-linux-amd64`
и `build/zig-cache` + `~/.cache/zig`, `~/.rustup`, `~/zig`, `~/android-sdk` и
`~/.gradle/caches/build-cache-1`. Ключи привязаны к `Cargo.lock`,
`rust-toolchain.toml`, `gradle.properties` и — у каждого toolchain-кеша — к
`hashFiles('scripts/toolchain.env')`, поэтому смена любого пина не оставляет
старые артефакты. Проверка корректности не делегируется кешу: cargo
сам инвалидирует по исходникам, а provenance/verify сверяют результат.

Шаг `Gate: CI and release script fixtures` прогоняет все `.github/tests/*.sh`,
включая `zig-env.sh` (сверяет ambient-список и пины `zig`/`cargo-zigbuild`). Это
дешёвый гейт против регрессий в скриптах версий, релиза, подписи и провенанса:
раньше они гонялись только локально.

Релиз запускается по тегу `v*` либо `workflow_dispatch` с именем существующего тега (workflow
тегов не создаёт).

### Гейт релизного тега

Тег обязан совпадать с `^v[0-9]+\.[0-9]+\.[0-9]+$` — тег вида `v2.5.2-rc1` релиз не соберёт (workflow запустится, но гейт тега его остановит).
Коммит тега обязан быть достижим из `origin/main`. Собирается **дерево коммита тега**, а не HEAD
ветки: `resolve` вычисляет коммит тега и передаёт его в `build.yml` как `ref`, а `publish` отдельно
делает `git checkout --detach <commit тега>` — скрипты сборки и публикации берутся из дерева тега.

### Что попадает в релиз

- `recsqtt-<version>-universal.apk`, `recsqtt-<version>-arm64-v8a.apk`, `recsqtt-<version>-armeabi-v7a.apk`;
- `recsqtt-linux-amd64`;
- `install.sh` — установщик сервера; GitHub не хранит режим файла, поэтому бит `+x` теряется и запускать нужно `bash install.sh`;
- `bootstrap.sh` — тонкий вход one-liner'а: скачивает `install.sh` и `SHA256SUMS` того же релиза, проверяет SHA-256 и запускает установщик с проброшенными аргументами;
- `SHA256SUMS` — по шести файлам, совместим с `sha256sum -c`.

`recsqtt-x86_64.apk` отдельным ассетом не публикуется: x86_64 остаётся внутри universal-APK, этого
хватает эмуляторам. Права на исполнение при скачивании теряются — серверу нужен
`chmod +x recsqtt-linux-amd64`.

Всего публикуются семь файлов (`bootstrap.sh`, `install.sh`, три APK, сервер и `SHA256SUMS`) — шесть записей
в контрольных суммах.
При повторном использовании каталога `--out` сборщик удаляет только старые
`csqtt-linux-arm64`/`csqtt-linux-armv7`; посторонние файлы сохраняет, но не включает
в список публикации и контрольные суммы. Служебный `asset-paths.txt` также
не публикуется.

### Совместимость 2.5.2

В описании релиза **2.5.2** укажите владельцам профилей: после обновления
приложение — только участник, хост, порт и пароль задаются исключительно
ссылкой `csqtt://`. Импортируйте ссылку из панели (карточка клиента/QR); поля,
заполненные вручную, удалены, автоматической миграции нет. Сервер ставится и
обновляется через `install.sh`/`csqttctl`, не из приложения. Выгрузка настроек
и правил приложений в файл или буфер обмена и обратная загрузка из приложения
удалены.

### Секреты

| Секрет | Содержимое |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `release.keystore`, base64 без переносов строк |
| `ANDROID_KEYSTORE_PASSWORD` | пароль хранилища |
| `ANDROID_KEY_ALIAS` | алиас ключа |
| `ANDROID_KEY_PASSWORD` | пароль ключа |
| `ANDROID_KEYSTORE_FINGERPRINT` | ожидаемый SHA-256 сертификата (hex, без `:`) |

```bash
# значения читаются из файла или скрытого интерактивного ввода — в историю оболочки не попадают
base64 -w0 release.keystore > keystore.b64              # Linux
base64 < release.keystore | tr -d '\n' > keystore.b64   # macOS
gh secret set ANDROID_KEYSTORE_BASE64 -R w3struk/recsqtt < keystore.b64
rm -f keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD -R w3struk/recsqtt   # gh спросит скрытым вводом
gh secret set ANDROID_KEY_ALIAS         -R w3struk/recsqtt
gh secret set ANDROID_KEY_PASSWORD      -R w3struk/recsqtt

# ожидаемый отпечаток сертификата
keytool -list -v -keystore release.keystore -alias csqtt_release_key \
  | grep -m1 'SHA256:' | sed 's/.*SHA256:[[:space:]]*//' | tr -d '[:space:]:'
gh secret set ANDROID_KEYSTORE_FINGERPRINT -R w3struk/recsqtt
```

Отпечаток дублируется в репозитории — `scripts/release_signing_fingerprint.txt` (одна строка,
uppercase, без двоеточий). Это источник правды: коммит нельзя подменить молча, тогда как секрет
подменяет админ. Workflow сверяет keytool ↔ файл ↔ секрет, и расхождение любой пары останавливает
релиз до публикации. **Ротация ключа:** сначала обновить файл и закоммитить, затем секрет — так гейт
ни на минуту не становится мягким.

### Порядок первого релиза

1. Коммит с `.github/` и `scripts/release_signing_fingerprint.txt` уходит в `origin/main`: workflow
   из файла на коммите тега иначе не запустится, а гейт достижимости не пройдёт.
2. `git tag v2.5.2 && git push origin v2.5.2`. Первый прогон и есть проверка пайплайна — и это
   безопасно: всё, что можно проверить, проверяется до публикации, поэтому упавший прогон не
   оставляет релиза, только красную запись в Actions.
3. Если прогон упал: `git push origin :refs/tags/v2.5.2`, правка, новый тег. Опубликованный релиз
   при этом не появлялся, удалять нечего.

**Почему репетиция до первого релиза невозможна.** `workflow_dispatch` требует существующего тега,
но шаг `Checkout release target` делает `git checkout --detach <коммит тега>` и заменяет **всё**
рабочее дерево — включая `.github/scripts/`. У тега, срезанного до появления пайплайна, этих
скриптов нет, и прогон умирает на первом гейте. А любой semver-тег на `main` порождает push-событие,
то есть настоящий релиз, а не репетицию. Отсюда: репетировать можно только на теге, в чьём дереве
пайплайн уже лежит.

**Репетиция для последующих изменений workflow** (тег уже содержит пайплайн):

```bash
REPO=w3struk/recsqtt
SHA=$(git rev-parse origin/main)
# тег через API не порождает push-событие, поэтому реальный релиз не стартует
gh api -X POST "repos/$REPO/git/refs" -f ref=refs/tags/v0.0.1 -f sha="$SHA"
gh workflow run release.yml -R "$REPO" --ref main -f tag_name=v0.0.1 -F dry_run=true
gh run watch -R "$REPO"
gh api -X DELETE "repos/$REPO/git/refs/tags/v0.0.1"
```

Репетиция проходит тот же путь, что и релиз, но публикует не релиз, а артефакты прогона, и попутно
прогревает кеши: прогон по тегу чужой кеш не видит, поэтому каждый релиз без прогрева — холодный.

### Проверка релиза

Скачать ассеты и сверить: `sha256sum -c SHA256SUMS`; `apksigner verify --print-certs <apk>` —
отпечаток должен совпасть с `scripts/release_signing_fingerprint.txt`; в релизе не должно быть
`recsqtt-x86_64.apk`. В APK не должно быть `assets/recsqtt-linux-amd64` и исторического `deploy.sh`.

### Повторная публикация и откат

Опубликованный релиз workflow **не трогает** — повторный прогон того же тега падает с внятным
сообщением. Остаточный черновик (job убит по таймауту или отменён) удаляется по id и создаётся
заново; тег при этом остаётся. Ручное удаление: `gh release delete <tag>`, затем
`git push origin :refs/tags/<tag>`. Локальная сборка `bash scripts/build_apk.sh` остаётся рабочим
запасным путём.

### Грабли, которые видно только на втором прогоне

Локальный стенд (`bash .github/scripts/test-local.sh all`) не воспроизводит две вещи —
они проявляются только когда прогон упирается в кеш или в маскирование секретов.

**1. `cargo install` не перезаписывает уже установленный бинарь.** `~/.cargo/bin` лежит
в кеше `Cache Cargo binaries`, поэтому на первом прогоне кеш пуст, установка проходит, а
на втором бинарь уже на месте — и `cargo install` падает с `exit 101`:

```
error: binary `cargo-ndk` already exists in destination
```

Ловится только повторным прогоном: локально кеша нет, и `cargo install` каждый раз
ставит всё с нуля. Поэтому шаг установки сверяет версию целой строкой
(`cargo-ndk --version` печатает ровно `cargo-ndk <версия>`; подстрока `4.1.2` совпала бы
и с `4.1.20`) и, если версия та, ничего не ставит; если другая или бинаря нет —
переустанавливает с `--force`.

**2. Выход job'а, значение которого совпадает с секретом, GitHub не передаёт.** Отпечаток
сертификата равен значению `ANDROID_KEYSTORE_FINGERPRINT`, поэтому в логе прогона
появляется предупреждение

```
! Skip output 'fingerprint' since it may contain secret.
```

и `needs.build.outputs.fingerprint` в вызывающем job'е приходит **пустым** — без ошибки,
просто выход не передан. Для описания релиза это значило бы, что строка `Подпись:`
исчезает молча. Поэтому отпечаток берётся из `scripts/release_signing_fingerprint.txt`
(того же файла, который `build.yml` сверял тройной сверкой), а сам выход `fingerprint`
из `build.yml` убран. Правило общее: **значение, равное секрету, нельзя гонять через
выход job'а** — только из файла в репозитории.

## Бета-канал (Telegram)

Пуш в `main` собирает бету и отправляет её в Telegram-канал, куда подписаны владелец и тестеры.
Пайплайн — `.github/workflows/beta.yml`; гейты и сборка берутся из общего
`.github/workflows/build.yml`, поэтому состав проверок тот же, что у релиза.

### Триггеры и версия

- `push` в `main`; `workflow_dispatch` — как предохранитель, **только с `main`** (запуск с
  фича-ветки отправил бы тестерам незамерженный код, и `beta-version.sh` такой запуск отвергает).
- Версия беты — `<versionName>-beta.<short-sha>`, например `2.5.4-beta.a3323ff`: номер виден и в
  имени файла, и в приложении. `versionCode` не меняется, поэтому тестер может поставить релиз
  поверх беты без переустановки.
- Собирается только `arm64-v8a` (при одном ABI universal-APK не создаётся). В канал уходит **только
  APK**: amd64-сервер собирается и лежит в артефакте прогона, но не рассылается. GitHub-релиз не
  создаётся, тег не ставится.

### Секреты

| Секрет | Содержимое |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | токен бота |
| `TELEGRAM_CHAT_ID` | id канала (`-100…`); бот должен быть администратором канала |

Пять `ANDROID_KEYSTORE_*` переиспользуются — бета подписана тем же ключом, что релиз.

### Поведение

- Красный прогон в канал не пишет ничего: молчание канала означает, что сборка не прошла.
- **`concurrency` здесь намеренно нет.** У GitHub по умолчанию `queue: single`: новый прогон в той
  же группе отменяет ожидающий, и при трёх пушах подряд второй коммит не собрался бы вовсе.
  Прогоны независимы и могут идти параллельно.
- `gh run rerun` отправит в канал **второй** документ: идемпотентности нет ни у GitHub, ни у
  скрипта. Повторять — только осознанно.

### Артефакт прогона

`build.yml` публикует артефакт `release-assets-<version>` с каталогами `apk/` (то, что собрал
Gradle, с именами `app-<version>-<abi>-release.apk`) и `assets/` (`recsqtt-linux-amd64`,
собранный CI; в APK его нет). Готовые имена
`recsqtt-*.apk` и `SHA256SUMS` создаёт `collect-release-assets.sh` уже у вызывающего — поэтому в
артефакте прогона их нет, и проверять скачанный артефакт надо по `apk/`/`assets/`, а семь имён и
`sha256sum -c` — на настоящем релизе.
