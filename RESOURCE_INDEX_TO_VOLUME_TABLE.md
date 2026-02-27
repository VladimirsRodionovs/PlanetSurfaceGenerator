# Resource Index -> Physical Volume Table

This table maps `amount` index (`1..100`) to a reference physical resource volume for each `ResourceType`.

Assumptions (match current generator behavior):
- Uses `ResourceGenerator.computeLogTonnes(...)` with neutral quality/saturation (`quality=50`, `saturation=50`) and zero random noise.
- For resources with a `logRange`, expected log value at index `A` is:
  - `log10(V_tonnes) = min + (max - min) * (0.2 + 0.6 * A / 100)`
- Units:
  - `V` is tonnes for all deposit-like resources, including hydrocarbons.

Absolute spread model (current generator):
- Random spread is log-normal with `sigma = max(0.25, (max-min)/5)` in log10-space.
- For most resources, this means about:
  - ~68% of samples within `V_base / 10^sigma .. V_base * 10^sigma`
  - ~95% within `V_base / 10^(2*sigma) .. V_base * 10^(2*sigma)`
- Values are clipped into `[10^min, 10^max]`.

Tile scale note:
- One tile is a hex with flat-to-flat width about `100..120 km`.
- Hex area by flat-to-flat width `D`: `A_hex = (sqrt(3)/2) * D^2`.
- For `D=100..120 km`, tile area is about `8,660..12,470 km^2`.

## Reference Anchors Used For Calibration
- USGS Porphyry Cu deposit model: https://www.usgs.gov/publications/porphyry-copper-deposit-model
- USGS VMS occurrence model: https://www.usgs.gov/publications/volcanogenic-massive-sulfide-occurrence-model
- USGS MVT Pb-Zn model: https://www.usgs.gov/publications/mississippi-valley-type-lead-zinc-deposit-model
- USGS SEDEX model: https://www.usgs.gov/publications/sedimentary-exhalative-sedex-zinc-lead-silver-deposit-model
- USGS Ni-Co laterite model: https://www.usgs.gov/publications/nickel-cobalt-laterites-a-deposit-model
- USGS sediment-hosted gold model: https://www.usgs.gov/publications/sediment-hosted-gold-deposits-world-database-and-grade-and-tonnage-models
- USGS PRB coal assessment: https://www.usgs.gov/publications/coal-geology-and-assessment-coal-resources-and-reserves-powder-river-basin-wyoming-and
- Giant oil/gas threshold reference: https://www.ogj.com/home/article/17220333/giant-fields-then-and-now

## Per-Resource Index Mapping (reference expected values)

| id | code | unit | log_min | log_max | V(A=1) | V(A=50) | V(A=100) |
|---:|---|---|---:|---:|---:|---:|---:|
| 0 | Fe_HEM | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 1 | Fe_MAG | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 2 | Fe_SID | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 3 | Fe_LIM | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 4 | Fe_GOE | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 5 | Fe_OOL | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 6 | Fe_TMAG | tonnes | 6.50 | 8.50 | 8.166e+06 | 3.162e+07 | 1.259e+08 |
| 7 | Fe_SAND | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 8 | Fe_BIF | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 9 | Fe_META | tonnes | 7.00 | 9.50 | 3.273e+07 | 1.778e+08 | 1.000e+09 |
| 10 | Mn_PYR | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 11 | Mn_CARB | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 12 | Mn_FERR | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 13 | Cr_MASS | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 14 | Cr_PLAC | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 15 | Cu_CHAL | n/a | - | - | n/a | n/a | n/a |
| 16 | Cu_BORN | n/a | - | - | n/a | n/a | n/a |
| 17 | Cu_CHZ | n/a | - | - | n/a | n/a | n/a |
| 18 | Cu_MAL | n/a | - | - | n/a | n/a | n/a |
| 19 | Cu_PORP | tonnes | 8.00 | 9.50 | 2.037e+08 | 5.623e+08 | 1.585e+09 |
| 20 | Cu_SKAR | tonnes | 5.50 | 8.00 | 1.035e+06 | 5.623e+06 | 3.162e+07 |
| 21 | Cu_VMS | tonnes | 4.50 | 8.50 | 2.109e+05 | 3.162e+06 | 5.012e+07 |
| 22 | ZnPb_SULF | tonnes | 5.50 | 8.50 | 1.312e+06 | 1.000e+07 | 7.943e+07 |
| 23 | ZnPb_CARB | tonnes | 5.50 | 8.50 | 1.312e+06 | 1.000e+07 | 7.943e+07 |
| 24 | ZnPb_OX | tonnes | 5.50 | 8.50 | 1.312e+06 | 1.000e+07 | 7.943e+07 |
| 25 | ZnPb_SEDEX | tonnes | 7.50 | 9.00 | 6.442e+07 | 1.778e+08 | 5.012e+08 |
| 26 | ZnPb_MVT | tonnes | 5.00 | 8.50 | 5.260e+05 | 5.623e+06 | 6.310e+07 |
| 27 | Ni_SULF | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 28 | Ni_LAT_L | tonnes | 6.40 | 8.60 | 7.132e+06 | 3.162e+07 | 1.445e+08 |
| 29 | Ni_LAT_S | tonnes | 6.40 | 8.60 | 7.132e+06 | 3.162e+07 | 1.445e+08 |
| 30 | Ni_ULTRA | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 31 | Co_LATER | tonnes | 6.40 | 8.60 | 7.132e+06 | 3.162e+07 | 1.445e+08 |
| 32 | Co_SULF | tonnes | 4.50 | 7.50 | 1.312e+05 | 1.000e+06 | 7.943e+06 |
| 33 | Al_BOX_L | tonnes | 7.00 | 9.00 | 2.582e+07 | 1.000e+08 | 3.981e+08 |
| 34 | Al_BOX_K | tonnes | 7.00 | 9.00 | 2.582e+07 | 1.000e+08 | 3.981e+08 |
| 35 | Al_NEPH | tonnes | 6.00 | 8.00 | 2.582e+06 | 1.000e+07 | 3.981e+07 |
| 36 | Al_ALUN | tonnes | 6.00 | 8.00 | 2.582e+06 | 1.000e+07 | 3.981e+07 |
| 37 | Ti_ILM | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 38 | Ti_RUT | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 39 | Ti_SAND | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 40 | Zr_CIRC | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 41 | Sn_CASS | tonnes | 5.50 | 8.00 | 1.035e+06 | 5.623e+06 | 3.162e+07 |
| 42 | Sn_PLAC | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 43 | W_SCHE | tonnes | 5.50 | 8.00 | 1.035e+06 | 5.623e+06 | 3.162e+07 |
| 44 | W_WOLF | tonnes | 5.50 | 8.00 | 1.035e+06 | 5.623e+06 | 3.162e+07 |
| 45 | Mo_PORP | tonnes | 8.00 | 9.50 | 2.037e+08 | 5.623e+08 | 1.585e+09 |
| 46 | Au_QUAR | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 47 | Au_PLAC | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 48 | PGM_NI | n/a | - | - | n/a | n/a | n/a |
| 49 | PGM_PLAC | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 50 | Ag_POLY | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 51 | REE_MON | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 52 | REE_BAST | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 53 | REE_ION | tonnes | 6.00 | 8.50 | 3.273e+06 | 1.778e+07 | 1.000e+08 |
| 54 | REE_APAT | tonnes | 5.00 | 7.50 | 3.273e+05 | 1.778e+06 | 1.000e+07 |
| 55 | U_URAN | tonnes | 4.50 | 7.00 | 1.035e+05 | 5.623e+05 | 3.162e+06 |
| 56 | U_SAND | tonnes | 5.00 | 8.00 | 4.150e+05 | 3.162e+06 | 2.512e+07 |
| 57 | U_PHOS | tonnes | 4.50 | 7.00 | 1.035e+05 | 5.623e+05 | 3.162e+06 |
| 58 | Th_MON | tonnes | 4.50 | 7.00 | 1.035e+05 | 5.623e+05 | 3.162e+06 |
| 59 | P_PHOS | tonnes | 7.00 | 9.00 | 2.582e+07 | 1.000e+08 | 3.981e+08 |
| 60 | S_PYR | n/a | - | - | n/a | n/a | n/a |
| 61 | S_GAS | n/a | - | - | n/a | n/a | n/a |
| 62 | F_FLUOR | n/a | - | - | n/a | n/a | n/a |
| 63 | B_BORAT | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 64 | Si_QUAR | n/a | - | - | n/a | n/a | n/a |
| 65 | Si_SAND | n/a | - | - | n/a | n/a | n/a |
| 66 | C_COAL | tonnes | 9.00 | 12.00 | 4.150e+09 | 3.162e+10 | 2.512e+11 |
| 67 | C_LIGN | tonnes | 9.00 | 12.00 | 4.150e+09 | 3.162e+10 | 2.512e+11 |
| 68 | C_GRAPH | n/a | - | - | n/a | n/a | n/a |
| 69 | Na_SALT | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 70 | K_SALT | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 71 | Li_BRINE | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 72 | Mg_BRINE | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 73 | Br_BRINE | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 74 | I_BRINE | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 75 | Na_BRINE | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 76 | Ca_BRINE | tonnes | 7.00 | 10.00 | 4.150e+07 | 3.162e+08 | 2.512e+09 |
| 77 | H2O_FRESH | n/a | - | - | n/a | n/a | n/a |
| 78 | H2O_SALT | n/a | - | - | n/a | n/a | n/a |
| 79 | HC_OIL_L | tonnes | 6.13 | 8.63 | 4.416e+06 | 2.399e+07 | 1.349e+08 |
| 80 | HC_OIL_H | tonnes | 6.13 | 8.63 | 4.416e+06 | 2.399e+07 | 1.349e+08 |
| 81 | HC_BITUM | tonnes | 6.13 | 8.13 | 3.483e+06 | 1.349e+07 | 5.370e+07 |
| 82 | HC_GAS | tonnes | 5.31 | 7.81 | 6.683e+05 | 3.631e+06 | 2.042e+07 |
| 83 | HC_COND | tonnes | 6.13 | 8.63 | 4.416e+06 | 2.399e+07 | 1.349e+08 |
| 84 | HC_SHALE | tonnes | 6.13 | 8.13 | 3.483e+06 | 1.349e+07 | 5.370e+07 |
| 85 | ATM_AIR | n/a | - | - | n/a | n/a | n/a |
| 86 | ATM_CO2 | n/a | - | - | n/a | n/a | n/a |
| 87 | ATM_H2S | n/a | - | - | n/a | n/a | n/a |
| 88 | ATM_NH3 | n/a | - | - | n/a | n/a | n/a |
| 89 | GEO_HEAT | n/a | - | - | n/a | n/a | n/a |
| 90 | WIND_PWR | n/a | - | - | n/a | n/a | n/a |
| 91 | SOLAR_PWR | n/a | - | - | n/a | n/a | n/a |
| 92 | HYDRO_PWR | n/a | - | - | n/a | n/a | n/a |
| 93 | TIDAL_PWR | n/a | - | - | n/a | n/a | n/a |
| 94 | FERTILITY | n/a | - | - | n/a | n/a | n/a |
| 95 | FERTILITY_NAT | n/a | - | - | n/a | n/a | n/a |
| 96 | AGRO_ZONE | n/a | - | - | n/a | n/a | n/a |
| 97 | IMPACT_GLASS | n/a | - | - | n/a | n/a | n/a |
| 98 | CH4_ICE_RES | n/a | - | - | n/a | n/a | n/a |
| 99 | NH3_ICE_RES | n/a | - | - | n/a | n/a | n/a |
| 100 | CO2_ICE_RES | n/a | - | - | n/a | n/a | n/a |
| 101 | H2O_ICE_RES | n/a | - | - | n/a | n/a | n/a |
| 102 | CRYO_VOLATILES | n/a | - | - | n/a | n/a | n/a |
| 103 | BIO_MAT | n/a | - | - | n/a | n/a | n/a |

## Notes for Implementation
- This is a **reference table** for balancing and tooltip display.
- Runtime generation includes stochastic spread (`gaussian noise`) and quality/saturation modifiers.
- For strict deterministic conversion (same value every time for same `amount`), random term in `computeLogTonnes` should be disabled or moved to a separate modifier.
