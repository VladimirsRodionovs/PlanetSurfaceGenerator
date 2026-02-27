# Planet Surface Generator

Procedural surface generation tool for planets and moons.

The project loads source astro-objects from `StarSystems`, runs a deterministic tile-based simulation pipeline, and stores compact HexData output in `PlanetsSurfaces2`.

## Highlights
- Multi-stage deterministic generation pipeline.
- Relief, climate, hydrology, biome, and resource synthesis.
- UI mode and batch mode.
- MySQL-backed input/output workflow.

## Tech Stack
- Java 17
- Maven
- JavaFX 21
- MySQL Connector/J
- HikariCP
- Jackson

## Quick Start
1. Create local DB config:
   - `mkdir -p local`
   - `cp db.local.properties.example local/db.local.properties`
2. Build:
   - `mvn -q -DskipTests compile`
3. Run UI:
   - `mvn -q javafx:run`
4. Run batch:
   - `mvn -q -DskipTests exec:java -Dexec.mainClass=org.planet.app.BatchMain`

## Documentation
- Internal design: `ARCHITECTURE.md`
- Destination schema: `PLANETS_SURFACES_SCHEMA.sql`
- Resource model docs: `RESOURCES.md`

## Contact
vladimirs.rodionovs@gmail.com
