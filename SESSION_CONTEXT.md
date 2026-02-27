# Session Context (PlanetSurfaceGenerator)
Date: 2026-02-13

## Scope of current session
- Main work focused on climate moisture transport + evaporation/precipitation realism + river system rewrite.
- Project path: `/home/vladimirs/PlanetSurfaceGenerator/planet-generator`.

## Climate/Wind/Moisture: current state
- Wind display is in `m/s` (conversion from internal units is active).
- Moisture transport in `WindGenerator` was reworked around IWV-like transport and then tuned:
  - fixed internal-vs-physical wind speed usage in advection,
  - updated moisture layer handling (near-surface layer instead of overly deep column dilution),
  - improved evaporation treatment for wetlands/water classes,
  - added pressure-aware humidity conversions,
  - reduced striping artifacts and excessive over-ocean wind/precip extremes.
- Important physical fields now kept on tiles:
  - `atmMoist` interpreted as `g/kg`,
  - `precipKgM2Day`, `evapKgM2Day` (`kg/m^2/day`, equivalent to `mm/day` for water).

## Mass-conservation fix
- Found that scalar smoothing of moisture could be non-conservative on irregular mesh.
- Replaced moisture mixing calls with conservative pairwise flux mixing:
  - `mixScalarConservative(...)` in `WindGenerator`.
- This preserves total redistributed moisture except explicit source/sink terms (evap/precip/rainout).

## UI / tooltip updates
- Tooltip now shows physical hydro fluxes and units:
  - `PrecipPhys=... kg/m2/day`
  - `EvapPhys=... kg/m2/day`
  - `AtmMoist` and saturation cap labeled in `g/kg`.
- River tooltip now shows:
  - base type,
  - discharge (`kg/s`, `t/s`),
  - river tag.

## Dump format updates
- Dump headers in both UI and batch include:
  - `precip_kgm2day`, `evap_kgm2day`
  - `river_kgs`, `river_tps`
  - `riverBase`, `riverTag`
- Dump rotation active: `current`, `_prev1`, `_prev2`.

## River model rewrite (major)
- Old `RiverGenerator` removed and replaced with physical runoff/channel model.
- New model:
  - soil reservoir in `kg/m^2` with capacity by surface type,
  - conservative lateral groundwater redistribution,
  - source runoff from `P-E`, soil overflow/spring terms,
  - discharge routed downslope in `kg/s`,
  - lake overflow and limited uphill carving for strong flows,
  - swamps from stagnant oversaturated ground with poor outlet.
- Added tile fields:
  - `riverDischargeKgS`, `riverDischargeTps`,
  - `riverBaseType` (enum),
  - `riverTag` format: `BASE_TO_FROM_FROM...`.
- New enum:
  - `RiverBaseType`: `SOURCE`, `SMALL_RIVER`, `MEDIUM_RIVER`, `LARGE_RIVER`, `VERY_LARGE_RIVER`, `VALLEY`, `CANYON`, `WATERFALL`, `DELTA`.
- Legacy `riverType` still filled for compatibility with old code paths.

## River visualization/status
- Rivers are now rendered by channel membership (`isRiver`), not by any positive transit flux.
- Fixed visual bug where oceans looked like rivers:
  - water tiles may carry transit discharge internally, but are not rendered as river channels.

## Biomes status
- Biome assignment currently uses climate balance (`precipAvg`, `evapAvg`, temperature, relief),
  not direct soil reservoir value.
- User plans to revisit biome table after river/climate stabilization.

## Known practical notes
- In this execution environment, JavaFX classes are unavailable for direct UI compilation checks.
- Core and batch classes compile successfully with `javac` for updated logic.

## Current user direction
- Continue with physically grounded hydrology/rivers first.
- Then retune biome generation tables using realistic evap/humidity assumptions.
