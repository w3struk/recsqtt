use super::support::*;

#[test]
fn item_names_are_unique_across_panel_modules() {
    // Модули видят друг друга через `pub(crate) use <mod>::*;`. Коллизия имён не
    // ломает сборку, пока имя нигде не использовано, а внутри модуля, который его
    // объявляет, локальное имя молча затеняет соседнее. Тест делает коллизию
    // громкой сразу, а не в момент первого обращения.
    let mut seen: std::collections::BTreeMap<String, String> = Default::default();
    for (file, source) in panel_sources() {
        for (_, line) in code_lines(&source) {
            let Some(name) = item_name(line) else {
                continue;
            };
            if let Some(other) = seen.insert(name.to_owned(), file.clone()) {
                panic!("`{name}` объявлено и в {other}, и в {file}");
            }
        }
    }
}

#[test]
fn panel_snapshots_are_cloned_under_the_database_guard() {
    // Снимок уходит в поток персистентности клоном, снятым под guard'ом базы:
    // `db.clone()` или `db.to_owned()` в аргументе `submit` — это копия `Database`
    // через deref guard'а, то есть согласованный снимок на момент удержания
    // блокировки.
    // Перечитывать базу после отпускания guard'а нельзя: следующая мутация
    // обгонит снимок в очереди персистентности.
    //
    // Предыдущая версия искала `drop(db);` и проходила вхолостую — таких мест в
    // панели нет. Счётчик ниже не даёт проверке снова остаться без предмета.
    let mut sites = 0usize;
    for (file, source) in panel_sources() {
        for (number, line) in code_lines(&source) {
            let Some(position) = line.find("db_persistence.submit(") else {
                continue;
            };
            sites += 1;
            let argument = &line[position..];
            assert!(
                argument.contains("db.clone()") || argument.contains("db.to_owned()"),
                "{file}:{number}: снимок БД должен сниматься копией под guard'ом"
            );
        }
    }
    assert!(
        sites > 0,
        "в панели больше нет снимков БД: проверка потеряла предмет, её надо перенацелить"
    );
}
