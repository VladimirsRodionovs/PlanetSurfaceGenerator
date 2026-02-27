# Planet Surface Generator

Procedural planet and moon surface generation tool for game development.

The project reads astronomical object data from MySQL, generates a tile-based world (icosahedron subdivision), simulates climate/terrain/biomes/resources, and writes compact surface payload back to DB.

## What it does

- loads planet or moon source data from `StarSystems`;
- builds tectonic and stress fields;
- generates relief, water, rivers, climate and seasonal variations;
- classifies biomes and relief regimes;
- generates resource distribution by geology + climate + hydrology;
- serializes output to compact HexData JSON and stores it in `PlanetsSurfaces2`.

## Entry points

- UI mode: `org.planet.app.Main`
- Batch mode: `org.planet.app.BatchMain`

## Tech stack

- Java 17
- Maven
- JavaFX 21
- MySQL (`mysql-connector-j`)
- HikariCP
- Jackson

## Repository structure

- `src/main/java/org/planet/app/Main.java` - interactive UI workflow.
- `src/main/java/org/planet/app/BatchMain.java` - batch generation / dump workflow.
- `src/main/java/org/planet/core/generation/` - generation pipeline and stages.
- `src/main/java/org/planet/core/db/` - DB config, repositories, loaders.
- `PLANETS_SURFACES_SCHEMA.sql` - destination table schema.
- `RESOURCES.md` and related docs - resource model documentation.

## Local DB config (safe)

This project does **not** require credentials in source code.
Create local config (ignored by git):

```bash
cd /home/vladimirs/PlanetSurfaceGenerator/planet-generator
mkdir -p local
cp db.local.properties.example local/db.local.properties
```

Example values:

```properties
db.url=jdbc:mysql://localhost:3306/EXOLOG
db.user=YOUR_DB_USER
db.password=YOUR_DB_PASSWORD
db.table.starsystems=StarSystems
db.table.surfaces=PlanetsSurfaces2
```

## Build

```bash
cd /home/vladimirs/PlanetSurfaceGenerator/planet-generator
mvn -q -DskipTests compile
```

## Run

### UI

```bash
mvn -q javafx:run
```

### Batch generation

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=org.planet.app.BatchMain -Dexec.args="2 200"
```

### Dump-request mode

```bash
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=org.planet.app.BatchMain \
  -Dexec.args="--dump-request /path/to/request.txt --out-dir /path/to/out"
```

## Pipeline overview

Generation stages execute in deterministic order (seeded):

`NEIGHBORS -> BASE_SURFACE -> PLATES -> STRESS -> OROGENESIS -> MOUNTAINS -> VOLCANISM -> CLIMATE -> WIND -> WATER_REBALANCE -> EROSION -> CLIMATE_RECALC -> IMPACTS -> ICE -> LAVA -> WATER_CLASSIFY -> RIVERS -> BIOMES -> RELIEF -> RESOURCES`

## Notes

- Geometry is tile-based lat/lon over subdivided icosahedron.
- Output format uses compact keys (`sv`, `gv`, `p`, `h`) documented in `HEXDATA_DECODE.md`.
- For project internals see `PROJECT_CONTEXT.md`.
