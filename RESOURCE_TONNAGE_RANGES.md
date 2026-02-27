# Resource Tonnage Ranges (Order-of-Magnitude Anchors)

Goal: provide **real-world scale anchors** for generating deposit amounts with log-normal dispersion. Values below are *ore/deposit tonnage* unless noted. Source hydrocarbon anchors are in industry units, but generator ranges are converted to **log10(tonnes)**.

## Deposit-Scale Anchors (from sources)

| Group | Reported Range / Anchor | Implied log10(range) | Notes |
|---|---|---:|---|
| Porphyry Cu (ore) | “hundreds of millions to billions of metric tons of ore” | 8.0–9.5 | USGS model. citeturn0search0 |
| VMS (ore) | from <1 tonne to ~1.5 billion tonnes | 0.0–9.18 | USGS VMS model (very wide range). citeturn0search1 |
| MVT Zn/Pb (ore) | 0.06–394 Mt (tonnage model range) | 4.78–8.60 | USGS grade/tonnage model for MVT. citeturn0search3 |
| Sedex Zn/Pb (metal) | basins with >10 Mt Zn+Pb | ≥8.0 (for total ore ~100 Mt if 10:1 ore:metal) | USGS Sedex model + global perspective; **ore tonnage inferred**. citeturn0search2turn0search4 |
| Sediment-hosted Au (ore) | median tonnage ~7.1 Mt (Carlin subtype) | 6.85 | USGS model median (anchor for log-normal). citeturn0search5 |
| Ni laterite (ore) | 2.5–400 Mt | 6.40–8.60 | USGS laterite Ni model. citeturn0search6 |
| Coal basin (in-place, short tons) | 1.07 trillion short tons (PRB) | 12.03 | USGS PRB estimate; recoverable 162 billion short tons (11.21). citeturn0search7 |
| Oil “giant field” | ≥500 million barrels | 8.70 (barrels) | OGJ/AAPG giant field threshold. citeturn0search8 |
| Gas “giant field” | ≥3 Tcf | 12.48 (cubic feet) | OGJ/AAPG giant field threshold. citeturn0search8 |

## Suggested Generator Ranges (Initial)
Use these as **log10(tonnes)** ranges. These are calibrated to above anchors and may be tuned later.

| Group | Suggested log10 range | Notes |
|---|---:|---|
| Porphyry Cu/Mo | 8.0–9.5 | Huge deposits; use log-normal with sigma ~0.5–0.7. |
| VMS | 4.5–8.5 | Exclude extreme tails by default; allow rare outliers. |
| MVT | 5.0–8.5 | Based on USGS MVT range. |
| SEDEX | 7.5–9.0 | Inferred from “>10 Mt Zn+Pb” + ore:metal ratio. |
| Sediment-hosted Au (Carlin/SH) | 6.0–8.0 | Median anchor ~7.1 Mt. |
| Ni laterite / Co laterite | 6.4–8.6 | Large surface basins. |
| Coal / lignite basins | 9.0–12.0 | Basin scale (very wide). |
| Placers (Au/PGM/Sn/Ti/Zr/Fe sands) | 5.0–7.5 | District-scale placers. |
| Epithermal Au/Ag | 5.0–7.5 | Shallow, smaller bodies. |
| Skarn | 5.5–8.0 | Compact to medium. |
| BIF / Oolitic iron | 7.0–9.5 | Large stratiform bodies. |
| Brines / evaporites | 7.0–10.0 | Basin scale, highly variable. |
| Hydrocarbons | 5.31–8.63 (tonnes, by subtype) | Converted from barrel/ft^3 anchors to tonnes in generator (`HC_GAS` lower band; oil-like higher band). |

## Notes
- Where sources report **metal tonnage** (e.g., SEDEX Zn+Pb), total ore tonnage was inferred and should be treated as an approximation.
- Hydrocarbon source literature is commonly reported in **barrels (oil)** or **Tcf (gas)**; generator internal ranges are converted to tonnes.
