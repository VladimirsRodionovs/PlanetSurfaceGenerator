# ARCHITECTURE — Planet Surface Generator

## Назначение
`planet-generator` создает процедурную поверхность планет/спутников: рельеф, климат, гидрологию, биомы и ресурсы. Результат сериализуется в compact HexData JSON и сохраняется в `PlanetsSurfaces2`.

## Технологический стек
- Java 17
- Maven
- JavaFX 21
- MySQL Connector/J
- HikariCP
- Jackson

## Режимы работы
- UI: `org.planet.app.Main`
- Batch: `org.planet.app.BatchMain`

## Высокоуровневый поток
1. Загрузка объекта (планета/спутник) из `StarSystems`.
2. Построение геометрии тайлов и соседств (икосаэдральная топология).
3. Последовательный запуск generation pipeline (seeded, deterministic order).
4. Расчет климата, воды, рек, биомов, ресурсов.
5. Сериализация (`HexData`) и upsert в `PlanetsSurfaces2`.

## Слои
### 1) App / orchestration
- Пакет: `org.planet.app`
- Классы:
  - `Main` — интерактивный запуск;
  - `BatchMain` — пакетная генерация и dump-request сценарии.

### 2) Service layer
- Пакет: `org.planet.core.service`
- Класс: `PlanetGenerationService`
- Роль: координация full-cycle генерации и сохранения.

### 3) Generation engine
- Пакет: `org.planet.core.generation`
- Ключевые элементы:
  - `GenerationPipeline`, `StageId`, `GenerationStage`, `WorldContext`;
  - профиль/настройки: `PlanetTuning`, `GeneratorSettings`;
  - генераторы: climate, tectonics, erosion, lava, rivers, biomes, resources.
- Stage-пакет: `org.planet.core.generation.stages`
  - реализация шагов pipeline в фиксированном порядке.

### 4) Topology / geometry
- Пакет: `org.planet.core.topology`
- Классы:
  - `IcosaNeighborsBuilder`, `NeighborGraphBuilder`
- Роль: граф соседств и геометрическая основа для симуляций.

### 5) Data model
- Пакеты: `org.planet.core.model`, `org.planet.core.model.config`
- Сущности: `Tile`, `TectonicPlate`, `SurfaceType`, конфиги планеты/климата.

### 6) DB layer
- Пакет: `org.planet.core.db`
- Ключевые классы:
  - `DataSourceFactory`, `LocalDbConfigLoader`, `DbConfig`;
  - `StarSystemRepository` — чтение входных объектов;
  - `PlanetSurfaceRepository` — запись результата;
  - `PlanetConfigMapper`, `MoonTideResolver` — маппинг/доп.обогащение.

### 7) IO/serialization
- Пакет: `org.planet.core.io`
- Классы: `HexDataEncoder`, `PlanetSurfaceSerializer`, `CsvTileLoader`, `TileSetSelector`.

## Pipeline (канонический порядок)
`NEIGHBORS -> BASE_SURFACE -> PLATES -> STRESS -> OROGENESIS -> MOUNTAINS -> VOLCANISM -> CLIMATE -> WIND -> WATER_REBALANCE -> EROSION -> CLIMATE_RECALC -> IMPACTS -> ICE -> LAVA -> WATER_CLASSIFY -> RIVERS -> BIOMES -> RELIEF -> RESOURCES`

## Хранилище и интеграции
- Вход: MySQL `StarSystems` (астропараметры тел)
- Выход: MySQL `PlanetsSurfaces2` (HexData и метаданные)
- Upstream: `Starforge`
- Downstream: `ExodusServer` / клиентская визуализация

## Точки расширения
- Новый физический/экологический этап: добавить `GenerationStage` + включить в `GenerationPipeline`.
- Новая модель ресурсов: расширить `ResourceGenerator` и схемы индексов.
- Новый формат экспорта: добавить альтернативный сериализатор в `core/io`.

## Риски
- Очень длинный pipeline: риск скрытых зависимостей между этапами.
- Высокая вычислительная стоимость на больших батчах.
- Требуется строгая совместимость HexData-контрактов при изменениях.

## Быстрая навигация
- Entry points: `src/main/java/org/planet/app/Main.java`, `src/main/java/org/planet/app/BatchMain.java`
- Pipeline core: `src/main/java/org/planet/core/generation/GenerationPipeline.java`
- Stages: `src/main/java/org/planet/core/generation/stages/`
- DB write: `src/main/java/org/planet/core/db/PlanetSurfaceRepository.java`
