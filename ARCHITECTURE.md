# ARCHITECTURE — Planet Surface Generator

## Purpose
`planet-generator` builds procedural surfaces for planets/moons: relief, climate, hydrology, biomes, and resources. Output is serialized to compact HexData JSON and stored in `PlanetsSurfaces2`.

## Tech stack
- Java 17
- Maven
- JavaFX 21
- MySQL Connector/J
- HikariCP
- Jackson

## Runtime modes
- UI mode: `org.planet.app.Main`
- Batch mode: `org.planet.app.BatchMain`

## High-level flow
1. Load source object (planet/moon) from `StarSystems`.
2. Build tile geometry and neighborhood graph (icosahedron topology).
3. Run deterministic generation pipeline (seeded).
4. Compute climate, water, rivers, biomes, resources.
5. Serialize to HexData and upsert into `PlanetsSurfaces2`.

## Layers
### 1) App / orchestration
- Package: `org.planet.app`
- Classes:
  - `Main` — interactive flow;
  - `BatchMain` — batch generation and dump-request workflow.

### 2) Service layer
- Package: `org.planet.core.service`
- Class: `PlanetGenerationService`
- Responsibility: full-cycle orchestration of generation + persistence.

### 3) Generation engine
- Package: `org.planet.core.generation`
- Core elements:
  - `GenerationPipeline`, `StageId`, `GenerationStage`, `WorldContext`;
  - tuning/config: `PlanetTuning`, `GeneratorSettings`;
  - generators: climate, tectonics, erosion, lava, rivers, biomes, resources.
- Stages package: `org.planet.core.generation.stages`
  - concrete pipeline steps in fixed order.

### 4) Topology / geometry
- Package: `org.planet.core.topology`
- Classes:
  - `IcosaNeighborsBuilder`, `NeighborGraphBuilder`
- Responsibility: neighborhood graph and geometric substrate.

### 5) Data model
- Packages: `org.planet.core.model`, `org.planet.core.model.config`
- Entities: `Tile`, `TectonicPlate`, `SurfaceType`, planet/climate config.

### 6) DB layer
- Package: `org.planet.core.db`
- Key classes:
  - `DataSourceFactory`, `LocalDbConfigLoader`, `DbConfig`;
  - `StarSystemRepository` — source object reads;
  - `PlanetSurfaceRepository` — result writes;
  - `PlanetConfigMapper`, `MoonTideResolver` — mapping/enrichment.

### 7) IO / serialization
- Package: `org.planet.core.io`
- Classes: `HexDataEncoder`, `PlanetSurfaceSerializer`, `CsvTileLoader`, `TileSetSelector`.

## Pipeline (canonical order)
`NEIGHBORS -> BASE_SURFACE -> PLATES -> STRESS -> OROGENESIS -> MOUNTAINS -> VOLCANISM -> CLIMATE -> WIND -> WATER_REBALANCE -> EROSION -> CLIMATE_RECALC -> IMPACTS -> ICE -> LAVA -> WATER_CLASSIFY -> RIVERS -> BIOMES -> RELIEF -> RESOURCES`

## Storage and integrations
- Input: MySQL `StarSystems` (astro parameters)
- Output: MySQL `PlanetsSurfaces2` (HexData + metadata)
- Upstream: `Starforge`
- Downstream: `ExodusServer` / client rendering

## Extension points
- New physical/ecological stage: add `GenerationStage` + include in `GenerationPipeline`.
- New resource model: extend `ResourceGenerator` and index schemes.
- New export format: add serializer in `core/io`.

## Risks
- Long pipeline can hide inter-stage dependencies.
- High compute cost on large batches.
- HexData contract compatibility must be maintained across changes.

## Quick navigation
- Entry points: `src/main/java/org/planet/app/Main.java`, `src/main/java/org/planet/app/BatchMain.java`
- Pipeline core: `src/main/java/org/planet/core/generation/GenerationPipeline.java`
- Stages: `src/main/java/org/planet/core/generation/stages/`
- DB write: `src/main/java/org/planet/core/db/PlanetSurfaceRepository.java`
