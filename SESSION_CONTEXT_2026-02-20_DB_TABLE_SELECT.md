# Session Context (DB Table Selection)
Date: 2026-02-20
Project: `/home/vladimirs/PlanetSurfaceGenerator/planet-generator`

## Цель
Добавить выбор таблиц БД не только для batch, но и для любых взаимодействий с БД (UI + batch + dump mode).

## Что изменено

1. `DbConfig` расширен полями таблиц:
   - `starSystemsTable` (default: `StarSystems`)
   - `planetSurfacesTable` (default: `PlanetsSurfaces2`)
   - добавлен `applyOverridesFromSystem()` с поддержкой:
     - `-Dplanet.db.table.starsystems=...`
     - `-Dplanet.db.table.surfaces=...`
     - `PLANET_DB_TABLE_STARSYSTEMS`
     - `PLANET_DB_TABLE_SURFACES`

2. `StarSystemRepository`:
   - теперь принимает имя таблицы в конструкторе (`new StarSystemRepository(ds, table)`)
   - оставлен backward-compatible конструктор без имени (использует default)
   - добавлена валидация имени таблицы (`[A-Za-z0-9_]+`)

3. `PlanetSurfaceRepository`:
   - теперь принимает имя таблицы в конструкторе (`new PlanetSurfaceRepository(ds, table)`)
   - оставлен backward-compatible конструктор без имени (использует default)
   - везде убран хардкод `TABLE_NAME`, используется `tableName`
   - добавлена валидация имени таблицы (`[A-Za-z0-9_]+`)

4. `Main` (UI):
   - читает table overrides через `cfg.applyOverridesFromSystem()`
   - все репозитории создаются с таблицами из `DbConfig`
   - save/batch UI path работает через те же таблицы

5. `BatchMain`:
   - добавлен единый `buildDbConfig(args)`
   - поддержка CLI-опций:
     - `--star-table <table>`
     - `--surface-table <table>`
     - (дополнительно) `--db-url`, `--db-user`, `--db-password`
   - параметры применяются и в обычном batch, и в `--dump-request` режиме
   - в `BATCH_START` лог добавлены `srcTable=... dstTable=...`

## Проверки

1. Компиляция прошла:
   - `DbConfig`, `StarSystemRepository`, `PlanetSurfaceRepository`, `BatchMain`, `Main`.

2. Runtime проверка batch с явным выбором таблиц:
   - запуск: `BatchMain --star-table StarSystems --surface-table PlanetsSurfaces2 43 43`
   - успешно: `systemsOk=1`, `systemsFail=0`
   - в логе:
     - `[BATCH_START] ... srcTable=StarSystems dstTable=PlanetsSurfaces2`

## Измененные файлы

- `src/main/java/org/planet/core/db/DbConfig.java`
- `src/main/java/org/planet/core/db/StarSystemRepository.java`
- `src/main/java/org/planet/core/db/PlanetSurfaceRepository.java`
- `src/main/java/org/planet/app/Main.java`
- `src/main/java/org/planet/app/BatchMain.java`
- `SESSION_CONTEXT_2026-02-20_DB_TABLE_SELECT.md` (новый)
