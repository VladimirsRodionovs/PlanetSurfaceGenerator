# Project Context

## Purpose
Planet surface generator for game development tools. Not runtime.

## Entry Points
`src/main/java/org/planet/app/Main.java` for UI run.
`src/main/java/org/planet/app/BatchMain.java` for batch generation.

## Database
Uses MySQL `EXOLOG`.
Reads from table `StarSystems` filtered by `StarSystemID` (system index).
Reads `ObjectInternalID`, `ObjectPlanetType`, `ObjectName`, `ObjectDescription`,
`ObjectOrbitMeanMotionPerDay`, `ObjectRotationInclination`,
`ObjectRotationSpeedSideric`, `ObjectRotationSpeed`, `ObjectProGrade`.
Writes to `PlanetsSurfaces2` with columns `StarSys`, `PlanetIdx`, `PlanetName`, `PlanetSeed`,
`HexDataBin`, `HexDataUSize`, `HexDataSizeEnc`.

## Tile Sets
File names in project root:
`LatLongTileID2_v2.txt`
`LatLongTileID3_v2.txt`
`LatLongTileID4_v2.txt`
`LatLongTileID5_v2.txt`
`LatLongTileID6_v2.txt` (not ready, fallback to ID5).
Selected by radius via `TileSetSelector`.

## Pipeline Overview
Stages in order:
`NEIGHBORS` -> `BASE_SURFACE` -> `PLATES` -> `STRESS` -> `OROGENESIS` -> `MOUNTAINS` -> `VOLCANISM`
-> `CLIMATE` -> `WIND` -> `WATER_REBALANCE` -> `EROSION` -> `CLIMATE_RECALC`
-> `IMPACTS` -> `ICE` -> `LAVA` -> `WATER_CLASSIFY` -> `RIVERS` -> `BIOMES` -> `RELIEF` -> `RESOURCES`.

Stage profiles are in `StageProfile`.
World classification is in `WorldClassifier`.

## Climate
`ClimateGenerator` uses mean or `teqK + greenhouse`.
Pressure affects latitude gradients.
`ClimateSampler` records `tempMin/tempMax`, `windAvg`, `precipAvg`, `sunnyDays`.
Seasonality includes axial tilt and orbital period.

## Wind
`WindGenerator` now uses belt circulation (Hadley/Ferrel/Polar), axial tilt shift, prograde/retrograde,
rotation speed factor, orography damping, and convergence/front zones. Tidal-locked planets use
terminator-focused flow.

## Rivers
`RiverGenerator` uses elevation + moisture for flow.
Rivers can cut small canyons when blocked.
River metadata on tiles:
`riverType` (0..6), `riverTo`, `riverFrom`, `riverFlow`, `riverOrder`.

## Relief
`ReliefClassifierGenerator` runs after biomes.
Creates combined surface types:
- `RIDGE_*`
- `CANYON_*`
- `BASIN_*`
One tile has exactly one type.

## Resources
Resource schema and indices in `RESOURCES.md`.
Resources are stored per tile as:
`[id, layer, quality, saturation, amount, logTonnes, tonnes]`.
Generator: `ResourceGenerator`.

## Serialization
`PlanetSurfaceSerializer` writes compact JSON:
- top-level keys: `sv`, `gv`, `p`, `h`.
- `p`: `{si, tc}` = `{subsurfaceIceThicknessMeters, tileCount}`.
- `h` entry format:
`[id, type, prefSeason, bReg, bMod, elevation,`
`"tMinSummer|tMinInter|tMinWinter", "tMaxSummer|tMaxInter|tMaxWinter",`
`"precipSummer|precipInter|precipWinter", "soilSummer|soilInter|soilWinter", "evapSummer|evapInter|evapWinter",`
`"windSummer|windInter|windWinter", "sunSummer|sunInter|sunWinter",`
`river, resources, tide, solar, ownerId, neighbors]`.
- `prefSeason`: `-1` unknown, `0` interseason, `1` summer, `2` winter.
- `bReg`: `BiomeRegime` index.
- `bMod`: `biomeModifierMask` bitmask.
- `river`: `[riverType, riverTo, riverDischargeKgS, from...]`.
- `resources`: `[[id, layer, quality, saturation, tonnes], ...]`.
- `tide`: `"rangeM|periodH"`.
- `solar`: `"kwhM2daySummer|kwhM2dayInter|kwhM2dayWinter"`.

## UI Notes
`MapRenderer` draws tiles and river overlays.
Selection dialog shows tiles file and world type.

## Determinism
All random choices are based on planet seed (system id + object id).
