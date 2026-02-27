# HexData Decode (Short Keys)

Top-level JSON keys:
- `sv` = schema version
- `gv` = generator version
- `p` = planet meta
- `h` = hex array

Planet meta `p`:
- `si` = `subsurfaceIceThicknessMeters`
- `tc` = `tileCount`

Each `h[i]` (one tile), by index:
1. `id`
2. `surfaceType`
3. `prefSeason` (`-1` unknown, `0` interseason, `1` summer, `2` winter)
4. `bReg` (`BiomeRegime` index, see table below)
5. `bMod` (`biomeModifierMask` bitmask)
6. `elevation`
7. `"tMinSummer|tMinInter|tMinWinter"`
8. `"tMaxSummer|tMaxInter|tMaxWinter"`
9. `"precipSummer|precipInter|precipWinter"`
10. `"soilSummer|soilInter|soilWinter"`
11. `"evapSummer|evapInter|evapWinter"`
12. `"windSummer|windInter|windWinter"`
13. `"sunSummer|sunInter|sunWinter"`
14. `river`
15. `resources`
16. `tide`
17. `solar`
18. `ownerId`
19. `neighbors`

Nested blocks:
- `river` = `[riverType, riverTo, riverDischargeKgS, from1, from2, ...]`
- `resources` = `[[id, layer, quality, saturation, tonnes], ...]`
- `tide` = `"rangeM|periodH"`
- `solar` = `"kwhM2daySummer|kwhM2dayInter|kwhM2dayWinter"`

Biome regime index `bReg`:
- `0` = `UNKNOWN`
- `1` = `CRYO`
- `2` = `BOREAL_CONTINENTAL`
- `3` = `TEMPERATE_BALANCED`
- `4` = `MONSOONAL`
- `5` = `ARID_STABLE`
- `6` = `ARID_SEASONAL`
- `7` = `TROPICAL_HUMID`
- `8` = `TROPICAL_DRYWET`
