# Resource Index And Rules

## Purpose
Single source of truth for resource indices, layers, and placement rules. Keep this file in sync with `ResourceType`.

## Layers
`0` SURFACE
`1` DEEP
`2` VERY_DEEP

## JSON Encoding
Each tile has `resources`:
`[ [id, layer, quality, saturation, amount, logTonnes, tonnes], ... ]`

`logTonnes` is log10 of estimated deposit size in tonnes for all resources, including hydrocarbons.
`tonnes` is the corresponding linear value in tonnes.

## Energy Resources
`90` WIND_PWR
`91` SOLAR_PWR
`92` HYDRO_PWR
`93` TIDAL_PWR
`89` GEO_HEAT
`94` FERTILITY
`95` FERTILITY_NAT
`96` AGRO_ZONE
`97` IMPACT_GLASS
`98` CH4_ICE_RES
`99` NH3_ICE_RES
`100` CO2_ICE_RES
`101` H2O_ICE_RES
`102` CRYO_VOLATILES
`103` BIO_MAT

## Water And Atmosphere
`77` H2O_FRESH
`78` H2O_SALT
`85` ATM_AIR
`86` ATM_CO2
`87` ATM_H2S
`88` ATM_NH3

## Metals And Ores
`0` Fe_HEM
`1` Fe_MAG
`2` Fe_SID
`3` Fe_LIM
`4` Fe_GOE
`5` Fe_OOL
`6` Fe_TMAG
`7` Fe_SAND
`8` Fe_BIF
`9` Fe_META
`10` Mn_PYR
`11` Mn_CARB
`12` Mn_FERR
`13` Cr_MASS
`14` Cr_PLAC
`15` Cu_CHAL
`16` Cu_BORN
`17` Cu_CHZ
`18` Cu_MAL
`19` Cu_PORP
`20` Cu_SKAR
`21` Cu_VMS
`22` ZnPb_SULF
`23` ZnPb_CARB
`24` ZnPb_OX
`25` ZnPb_SEDEX
`26` ZnPb_MVT
`27` Ni_SULF
`28` Ni_LAT_L
`29` Ni_LAT_S
`30` Ni_ULTRA
`31` Co_LATER
`32` Co_SULF
`33` Al_BOX_L
`34` Al_BOX_K
`35` Al_NEPH
`36` Al_ALUN
`37` Ti_ILM
`38` Ti_RUT
`39` Ti_SAND
`40` Zr_CIRC
`41` Sn_CASS
`42` Sn_PLAC
`43` W_SCHE
`44` W_WOLF
`45` Mo_PORP
`46` Au_QUAR
`47` Au_PLAC
`48` PGM_NI
`49` PGM_PLAC
`50` Ag_POLY
`51` REE_MON
`52` REE_BAST
`53` REE_ION
`54` REE_APAT
`55` U_URAN
`56` U_SAND
`57` U_PHOS
`58` Th_MON
`59` P_PHOS
`60` S_PYR
`61` S_GAS
`62` F_FLUOR
`63` B_BORAT
`64` Si_QUAR
`65` Si_SAND
`66` C_COAL
`67` C_LIGN
`68` C_GRAPH
`69` Na_SALT
`70` K_SALT
`71` Li_BRINE
`72` Mg_BRINE
`73` Br_BRINE
`74` I_BRINE
`75` Na_BRINE
`76` Ca_BRINE
`79` HC_OIL_L
`80` HC_OIL_H
`81` HC_BITUM
`82` HC_GAS
`83` HC_COND
`84` HC_SHALE

## Placement Rules Summary
Magmatic and ultramafic resources cluster near plate boundaries and high volcanism.
Hydrothermal resources appear near volcanism and water proximity.
Sedimentary resources depend on moisture and low relief.
Placers appear near water and low elevation.
Laterites require hot and wet climates.
Evaporites and brines require hot and dry climates.
Hydrocarbons require organics or life plus moisture.
Metamorphics depend on stress and elevation.
Energy resources use wind, sunny days, flow, and tidal or geothermal context.
Fertility is a surface resource derived from temperature, moisture, sunny days, and elevation.
Fertility_NAT targets natural biota and prefers higher moisture.
Agro_Zone is an integer category stored as quality (zone*20).
Impact_Glass appears on cratered/regolith surfaces.
Bio_Mat appears on forest tiles and scales with natural biomass availability.
Bio_Mat base tonnage assumes dense forest AGB ~250 t/ha and hex ~110 km flat-to-flat (~785k ha) -> ~2.0e8 t max.

## Deposit Distribution Profiles (Hex-Scale)
COMPACT: 1–3 hexes (magmatic/hydrothermal veins, skarns, small bodies).
CLUSTER: 3–20 hexes (small districts).
BELT: 10–150 hexes (linear belts and stratiform trends).
BASIN: 20–200+ hexes (sedimentary basins, large laterites, brines).
LINE: 3–30 hexes along rivers/shorelines (placers, beach sands).
PATCHY: 5–40 hexes scattered (bitumen and irregular accumulations).

LINE deposits are directionally biased (continue along river flow or shoreline).
LINE deposits may branch rarely (low probability) and have decay with distance (growth may stop as length increases).

## Layer Weights (Surface / Deep / VeryDeep)
Hydrocarbons:
HC_OIL_L 0.2/0.7/0.1
HC_OIL_H 0.3/0.6/0.1
HC_GAS 0.1/0.7/0.2
HC_COND 0.1/0.8/0.1
HC_SHALE 0.4/0.6/0.0
HC_BITUM 0.8/0.2/0.0
C_COAL/C_LIGN 0.9/0.1/0.0

Metals:
Au_QUAR/Ag_POLY 0.6/0.4/0.0
Cu_PORP/Mo_PORP 0.1/0.7/0.2
Cu_SKAR/W_SCHE/W_WOLF/Sn_CASS 0.3/0.5/0.2
Cu_VMS/ZnPb_SULF 0.5/0.5/0.0

Sedimentary / surface:
Ni_LAT*/Co_LATER/Al_BOX* 1.0/0.0/0.0
Placers (Au/Sn/PGM/Ti/Zr/Fe sands) 1.0/0.0/0.0
Brines/evaporites 0.8/0.2/0.0

Profiles by type (selected):
Fe_BIF/Fe_OOL -> BELT, Fe_TMAG -> COMPACT, Fe_SAND -> LINE, Fe_META -> CLUSTER.
Cu_PORP/Mo_PORP -> CLUSTER, Cu_SKAR/Cu_VMS -> COMPACT.
ZnPb_SEDEX/U_SAND/U_PHOS/P_PHOS -> BELT.
Ni_LAT*/Co_LATER/Al_BOX* -> BASIN.
Placers (Au/Sn/PGM/Ti/Zr/Fe sands) -> LINE.
Coal/bitumen/hydrocarbons -> BASIN or PATCHY (bitumen).
Brines/evaporites -> BASIN.
REE_ION -> BASIN, REE hard-rock -> COMPACT.

## Rarity Tiers (Realism)
Very common (x1.0): Si_SAND, Si_QUAR, H2O_SALT, H2O_FRESH, ATM_AIR, WIND_PWR, SOLAR_PWR.
Common (x0.6): Fe_*, Mn_*, Al_*, Na/K/Br/I/Mg/Li/Ca brines, C_COAL/LIGN/GRAPH, F_FLUOR, B_BORAT, BIO_MAT.
Rare (x0.25): Cu_*, ZnPb_*, Ni_*, Co_*, Ti_*, Zr_CIRC, Sn_*, W_*, Mo_PORP, Ag/Au, U_*, S_*, HC_*, GEO/ HYDRO/ TIDAL, FERTILITY, AGRO_ZONE, ATM_CO2/H2S/NH3.
Ultra-rare (x0.1): PGM_*, REE_*, Th_MON, IMPACT_GLASS.
Ice volatiles (x0.25 base, boosted on ICE_VOLATILE): CH4_ICE_RES, NH3_ICE_RES, CO2_ICE_RES.
Deep cryo resources (x0.25 base, boosted on ICE worlds): H2O_ICE_RES, CRYO_VOLATILES.

## Quality Ranges (Base)
Magmatic/hydrothermal (Cr_MASS, Ni_ULTRA, PGM_NI, Ti_ILM, Fe_TMAG, Cu_PORP, Cu_SKAR, Cu_VMS, Mo_PORP, W_*, Sn_CASS, Au_QUAR, Ag_POLY): 55–90.
Sedimentary/chemical (Fe_BIF, Fe_OOL, Mn_CARB, ZnPb_SEDEX, ZnPb_MVT, P_PHOS, S_PYR, U_SAND): 35–80.
Laterites (Ni_LAT_*, Co_LATER, Al_BOX_*): 25–70.
Placers (Au_PLAC, Sn_PLAC, Ti_SAND, PGM_PLAC, Zr_CIRC, Fe_SAND): 60–95.
Clays/brines (REE_ION, Li/Mg/Br/I/Na/Ca brines, B_BORAT): 20–60.
Energy (WIND/SOLAR/HYDRO/TIDAL/GEO_HEAT): 40–95.
Water (H2O_FRESH/H2O_SALT): 80–100.
Atmosphere (ATM_*): 40–95.
Hydrocarbons (HC_*): 40–90.
Hard-rock REE/U/Th (REE_MON/BAST/APAT, U_URAN/U_PHOS, Th_MON): 45–85.
Default metals and others: 35–85.

## Quality Modifiers (Applied After Base Range)
Wet + warm climates reduce quality for metal ores and hydrocarbons (oxidation/alteration); dry climates slightly improve.
High volcanism/tectonic stress improves magmatic/hydrothermal ore quality.
Plate boundaries slightly reduce sedimentary ore quality.
Placers get a quality boost near rivers and water.
Laterites improve in hot/wet zones but drop in over-saturated areas.
Brines/evaporites improve in hot/dry zones.
Ice worlds reduce surface/deep metal ore quality, stronger with thicker subsurface ice.
Rock hardness affects quality (+/-): harder rock slightly improves.
Quality is weakly correlated with resource score.

## Planet-Type Modifiers
Metal ores are scaled by `fracIron` and `fracRock`.
Ice worlds reduce surface metal ores; thick subsurface ice further reduces surface ores.
Volatile atmospheres (ICE_VOLATILE) boost CO2/NH3 gas resources.
Volatile ices are boosted on ICE_VOLATILE and reduced on non-icy worlds.
No-water worlds reduce H2O resources.

## Update Rules
When adding new resources or indices, update:
`ResourceType`, this file, and any downstream consumers.
