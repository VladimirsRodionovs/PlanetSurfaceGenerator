package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.BiomeModifier;
import org.planet.core.model.BiomeRegime;
import org.planet.core.model.config.ClimateModelMode;
import org.planet.core.model.config.PlanetConfig;

import java.util.Arrays;
import java.util.List;

public class BiomeGeneratorV2 {

    public void apply(List<Tile> tiles, PlanetConfig planet, boolean hasLiquidWater, long seed, ClimateModelMode climateMode) {
        boolean hasLife = (planet == null) || planet.hasSurfaceLife;
        boolean physicalMode = climateMode == ClimateModelMode.PHYSICAL;
        double lifeMaxTempC = lifeMaxTemperatureC(planet);
        PlanetTuning.SeasonFavorabilityTuning favor = PlanetTuning.seasonFavorabilityTuning();
        // compute elevation percentiles to classify hills/mountains on this planet
        int[] elev = tiles.stream()
                .filter(t -> t.surfaceType != SurfaceType.OCEAN && t.surfaceType != SurfaceType.ICE_OCEAN && t.surfaceType != SurfaceType.LAVA_OCEAN)
                .mapToInt(t -> t.elevation)
                .sorted()
                .toArray();
        int p70 = percentile(elev, 0.70);
        int p85 = percentile(elev, 0.85);
        int minElev = elev.length > 0 ? elev[0] : 0;
        int maxElev = elev.length > 0 ? elev[elev.length - 1] : 1;

        int hydroCount = 0;
        for (Tile t : tiles) {
            if (!isWaterLike(t.surfaceType)) hydroCount++;
        }
        double[] peVals = new double[hydroCount];
        double[] soilVals = new double[hydroCount];
        int hk = 0;
        for (Tile t : tiles) {
            if (isWaterLike(t.surfaceType)) continue;
            double pPhys = Double.isNaN(t.precipKgM2Day) ? 0.0 : t.precipKgM2Day;
            double ePhys = Double.isNaN(t.evapKgM2Day) ? 0.0 : t.evapKgM2Day;
            peVals[hk] = pPhys - ePhys;
            soilVals[hk] = Double.isNaN(t.moisture) ? 0.0 : t.moisture;
            hk++;
        }
        double peP10 = percentile(peVals, 0.10);
        double peP90 = percentile(peVals, 0.90);
        double soilP10 = percentile(soilVals, 0.10);
        double soilP90 = percentile(soilVals, 0.90);

        for (Tile t : tiles) {
            t.biomeTempRange = Double.NaN;
            t.biomeAiAnn = Double.NaN;
            t.biomeAiWarm = Double.NaN;
            t.biomeAiCold = Double.NaN;
            t.biomeMonsoon = Double.NaN;
            t.biomeRegime = BiomeRegime.UNKNOWN;
            t.biomeModifierMask = 0;
            t.biomeWarmFromPositiveTilt = -1;
            t.biomePreferredSeason = -1;

            if (t.surfaceType == SurfaceType.OCEAN || t.surfaceType == SurfaceType.ICE_OCEAN || t.surfaceType == SurfaceType.LAVA_OCEAN
                    || t.surfaceType == SurfaceType.CRATERED_SURFACE || t.surfaceType == SurfaceType.REGOLITH
                    || t.surfaceType == SurfaceType.METHANE_ICE || t.surfaceType == SurfaceType.AMMONIA_ICE || t.surfaceType == SurfaceType.CO2_ICE
                    || t.surfaceType == SurfaceType.VOLCANIC || t.surfaceType == SurfaceType.VOLCANIC_FIELD
                    || t.surfaceType == SurfaceType.VOLCANO || t.surfaceType == SurfaceType.LAVA_PLAINS
                    || t.surfaceType == SurfaceType.LAVA_ISLANDS || t.surfaceType == SurfaceType.LAVA
                    || (!physicalMode && (t.surfaceType == SurfaceType.SWAMP || t.surfaceType == SurfaceType.MUD_SWAMP))
                    || t.surfaceType == SurfaceType.OPEN_WATER_DEEP || t.surfaceType == SurfaceType.OPEN_WATER_SHALLOW
                    || t.surfaceType == SurfaceType.LAKE_FRESH || t.surfaceType == SurfaceType.LAKE_SALT
                    || t.surfaceType == SurfaceType.LAKE_BRINE || t.surfaceType == SurfaceType.LAKE_ACID
                    || t.surfaceType == SurfaceType.SEA_ICE_SHALLOW || t.surfaceType == SurfaceType.SEA_ICE_DEEP
                    || t.surfaceType == SurfaceType.STEAM_SEA
                    || t.surfaceType == SurfaceType.COAST_SANDY || t.surfaceType == SurfaceType.COAST_ROCKY) {
                continue;
            }

            double tempAnnual = t.temperature;
            double tempWarm = fallbackSeason(t.biomeTempWarm, fallbackSeason(t.tempWarm, tempAnnual));
            double tempCold = fallbackSeason(t.biomeTempCold, fallbackSeason(t.tempCold, tempAnnual));
            double tempInter = fallbackSeason(t.biomeTempInterseason, tempAnnual);
            double precipPhys = Double.isNaN(t.precipKgM2Day) ? 0.0 : t.precipKgM2Day;
            double evapPhys = Double.isNaN(t.evapKgM2Day) ? 0.0 : t.evapKgM2Day;
            double precipWarm = fallbackSeason(t.biomePrecipWarm, fallbackSeason(t.precipWarm, precipPhys));
            double precipCold = fallbackSeason(t.biomePrecipCold, fallbackSeason(t.precipCold, precipPhys));
            double precipInter = fallbackSeason(t.biomePrecipInterseason, precipPhys);
            double precipPhysWarm = fallbackSeason(t.precipKgM2DayWarm, fallbackSeason(t.precipKgM2DayInterseason, precipPhys));
            double precipPhysCold = fallbackSeason(t.precipKgM2DayCold, fallbackSeason(t.precipKgM2DayInterseason, precipPhys));
            double precipPhysInter = fallbackSeason(t.precipKgM2DayInterseason, precipPhys);
            double evapWarm = fallbackSeason(t.biomeEvapWarm, fallbackSeason(t.evapWarm, evapPhys));
            double evapCold = fallbackSeason(t.biomeEvapCold, fallbackSeason(t.evapCold, evapPhys));
            double evapInter = fallbackSeason(t.biomeEvapInterseason, evapPhys);
            double pePhys = precipPhys - evapPhys;
            double aridity = (precipPhys + 0.2) / (evapPhys + 0.2); // <1 => суше
            double aiWarm = aridityIndex(precipWarm, evapWarm);
            double aiCold = aridityIndex(precipCold, evapCold);
            double aiInter = aridityIndex(precipInter, evapInter);
            double aiAnn = aridityIndex(precipPhys, evapPhys);
            double monsoon = Math.abs(precipWarm - precipCold) / (precipWarm + precipCold + 1.0);
            double tempRange = Math.max(0.0, tempWarm - tempCold);
            double soilAnnual = Double.isNaN(t.moisture) ? 0.0 : t.moisture;
            double soilWarm = fallbackSeason(t.biomeMoistureWarm, soilAnnual);
            double soilCold = fallbackSeason(t.biomeMoistureCold, soilAnnual);
            double soilInter = fallbackSeason(t.biomeMoistureInterseason, soilAnnual);
            double lifeThermalLimit = lifeTemperatureLimitC(t, planet, lifeMaxTempC);
            double thermalPeak = Math.max(tempWarm, Math.max(tempInter, Math.max(tempAnnual, tempCold)));

            if (t.surfaceType == SurfaceType.ICE_SHEET || t.surfaceType == SurfaceType.GLACIER) {
                continue;
            }

            double slope = maxSlope(t);
            boolean isMountain = t.surfaceType == SurfaceType.MOUNTAINS
                    || t.surfaceType == SurfaceType.HIGH_MOUNTAINS
                    || t.surfaceType == SurfaceType.MOUNTAINS_SNOW
                    || (t.elevation >= p85 && slope >= 4.0);
            boolean isHill = t.surfaceType == SurfaceType.HILLS
                    || t.surfaceType == SurfaceType.HILLS_GRASS
                    || t.surfaceType == SurfaceType.HILLS_FOREST
                    || t.surfaceType == SurfaceType.HILLS_RAINFOREST
                    || t.surfaceType == SurfaceType.HIGHLANDS
                    || t.surfaceType == SurfaceType.PLATEAU
                    || (!isMountain && t.elevation >= p70 && slope >= 2.0);

            double elevNorm = (maxElev > minElev) ? (t.elevation - minElev) / (double) (maxElev - minElev) : 0.0;
            SeasonChoice favored = pickMostFavorableSeason(
                    tempWarm, tempCold, tempInter,
                    precipWarm, precipCold, precipInter,
                    evapWarm, evapCold, evapInter,
                    soilWarm, soilCold, soilInter,
                    favor
            );
            t.biomePreferredSeason = favored.id();

            double tempAdj = favored.temp() - elevNorm * 12.0;
            double tempWarmAdj = tempWarm - elevNorm * 10.0;
            double tempColdAdj = tempCold - elevNorm * 10.0;
            double tempInterAdj = tempInter - elevNorm * 10.0;
            double peNorm;
            double soilNorm;
            double peFav = favored.precip() - favored.evap();
            if (physicalMode) {
                peNorm = normalizeToUnit(peFav, -1.0, 8.0);
                soilNorm = normalizeToUnit(favored.moisture(), 0.0, 100.0);
            } else {
                peNorm = normalizeToUnit(peFav, peP10, peP90);
                soilNorm = normalizeToUnit(favored.moisture(), soilP10, soilP90);
            }
            double hydro = peNorm * 0.45 + soilNorm * 0.55;
            double moistAdj = (hydro - 0.45) * 32.0 - elevNorm * 6.0;

            boolean coastalBuffered = hasWaterNeighbor(t);
            if (coastalBuffered) {
                moistAdj += 4.5;
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.COASTAL_BUFFERED);
            }

            // River-fed floodplains and oases should be greener than background climate.
            if (t.riverFlow > 0.03 || t.riverDischargeTps > 200.0) {
                moistAdj += 5.0;
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.RIVER_FED);
            }

            t.biomeTempRange = tempRange;
            t.biomeAiAnn = aiAnn;
            t.biomeAiWarm = aiWarm;
            t.biomeAiCold = aiCold;
            t.biomeMonsoon = monsoon;
            t.biomeRegime = classifyRegime(tempWarmAdj, tempColdAdj, aiAnn, aiWarm, aiCold, monsoon);

            if (tempRange >= 25.0) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.HIGH_SEASONALITY);
            }
            if (tempColdAdj <= -15.0) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.COLD_WINTER);
            }
            if (tempColdAdj <= -25.0) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.SEVERE_WINTER);
            }
            if (monsoon >= 0.45) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.MONSOONAL);
            }
            if (aiWarm > 1.1 && aiCold < 0.65) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.WET_SUMMER_DRY_WINTER);
            }
            if (aiWarm < 0.65 && aiCold > 1.1) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.DRY_SUMMER_WET_WINTER);
            }
            if (tempColdAdj < 0.0) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.FROST_RISK);
            }
            if (tempWarmAdj > 35.0) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.HEAT_STRESS);
            }
            // Дет. шум для неровности границ биомов
            double noise = octaveNoise(seed, t.id); // -1..1
            double tempJitter = noise * 1.2;
            double moistJitter = noise * 2.0;
            tempAdj += tempJitter;
            tempInterAdj += tempJitter;
            tempWarmAdj += tempJitter;
            moistAdj += moistJitter;
            if (!hasLiquidWater) {
                // нет жидкой воды -> ограничиваем влажные биомы
                moistAdj = Math.min(moistAdj, 35.0);
            }

            // Аридный режим: если испарение заметно выше осадков, принудительно сушим
            if (peFav < -0.2) {
                if (favored.ai() < 0.50) {
                    moistAdj -= 6.0;
                } else if (favored.ai() < 0.70) {
                    moistAdj -= 3.0;
                }
            }

            // Сильная сезонность: базируемся на теплом сезоне, но жесткая зима ограничивает "зеленость".
            double winterPenalty = 0.0;
            if (tempRange >= 30.0) winterPenalty += 2.0;
            if (tempColdAdj <= -15.0) winterPenalty += 3.0;
            if (tempColdAdj <= -25.0) winterPenalty += 3.0;
            if (aiCold < 0.55) winterPenalty += 1.5;
            tempAdj -= winterPenalty * 0.35;
            moistAdj -= winterPenalty;
            if (monsoon > 0.45 && aiWarm > 0.9) moistAdj += 2.0;
            if (monsoon > 0.45 && aiCold < 0.5) moistAdj -= 1.5;

            // Абсолютно перегретые зоны: принудительная пустыня только в жарко-сухом режиме.
            // Для жарко-влажных тайлов (монсун/тропики) оставляем выбор по профилям.
            double maxSeasonTempAdj = Math.max(tempWarmAdj, Math.max(tempInterAdj, tempAdj));
            boolean hotAndDry = favored.ai() < 1.0 || favored.moisture() < 45.0 || peFav < 0.0;
            if (maxSeasonTempAdj > 50.0 && hotAndDry) {
                if (isMountain || isHill) {
                    t.surfaceType = isHill ? SurfaceType.HILLS_DESERT : SurfaceType.MOUNTAINS_DESERT;
                } else {
                    t.surfaceType = SurfaceType.DESERT_ROCKY;
                }
                continue;
            }

            // Hard dry gate: if all seasonal physical precipitation and soil moisture are near zero,
            // force desert-like families to avoid grass/tundra artifacts at P~0 and E~0.
            boolean hyperArid = isHyperArid(
                    precipPhysWarm, precipPhysInter, precipPhysCold,
                    soilWarm, soilInter, soilCold
            );
            if (hyperArid) {
                if (isMountain || isHill) {
                    t.surfaceType = isHill ? SurfaceType.HILLS_DESERT : SurfaceType.MOUNTAINS_DESERT;
                } else {
                    t.surfaceType = (tempAdj <= 10.0) ? SurfaceType.COLD_DESERT : pickLowlandDesert(seed, t);
                }
                continue;
            }

            if (!hasLife) {
                applyAbiotic(t, tempAdj, moistAdj, isMountain, isHill);
                continue;
            }

            // Life-bearing biomes are forbidden above species tolerance and above local boiling constraints.
            if (thermalPeak > lifeThermalLimit) {
                t.biomeModifierMask = BiomeModifier.add(t.biomeModifierMask, BiomeModifier.HEAT_STRESS);
                applyAbiotic(t, tempAdj, moistAdj, isMountain, isHill);
                continue;
            }

            ReliefClass relief = isMountain ? ReliefClass.MOUNTAIN : (isHill ? ReliefClass.HILL : ReliefClass.LOWLAND);
            BiomeFeatures features = new BiomeFeatures(
                    tempAdj,
                    favored.precip(),
                    favored.evap(),
                    favored.moisture(),
                    favored.ai(),
                    tempWarmAdj,
                    tempColdAdj,
                    monsoon,
                    t.riverFlow > 0.03 || t.riverDischargeTps > 200.0,
                    coastalBuffered
            );
            SurfaceType selected = selectBiomeByProfiles(seed, t, relief, features);
            if (selected != null) {
                t.surfaceType = selected;
                continue;
            }

            // Fallback to legacy heuristics if profile table has no confident candidate.
            if (isHill) {
                t.surfaceType = pickHillBiome(tempAdj, moistAdj);
            } else if (isMountain) {
                t.surfaceType = pickMountainBiome(tempAdj, moistAdj);
            } else {
                if (tempAdj > 24) {
                    if (moistAdj > 12) {
                        t.surfaceType = SurfaceType.RAINFOREST;
                    } else if (moistAdj > 5) {
                        t.surfaceType = SurfaceType.SAVANNA;
                    } else {
                        t.surfaceType = SurfaceType.DRY_SAVANNA;
                    }
                } else if (tempAdj > 8) {
                    t.surfaceType = SurfaceType.GRASSLAND;
                } else {
                    t.surfaceType = SurfaceType.PLAINS_GRASS;
                }
            }
        }

        // Enforce one-step greener biome on river tiles (land only).
        for (Tile t : tiles) {
            if (!t.isRiver && t.riverDischargeTps <= 0.0) continue;
            if (isWaterLike(t.surfaceType)) continue;
            if (hasLife && tileExceedsLifeThermalLimit(t, planet, lifeMaxTempC)) continue;
            t.surfaceType = greenerByOneStep(t.surfaceType);
        }
    }

    private void applyAbiotic(Tile t, double tempAdj, double moistAdj, boolean isMountain, boolean isHill) {
        if (tempAdj < -5) {
            if (isMountain) {
                t.surfaceType = tempAdj < -15 ? SurfaceType.MOUNTAINS_SNOW : SurfaceType.MOUNTAINS_TUNDRA;
            } else if (isHill) {
                t.surfaceType = SurfaceType.HILLS_TUNDRA;
            } else {
                t.surfaceType = SurfaceType.TUNDRA;
            }
            return;
        }

        if (moistAdj < -5) {
            if (isMountain || isHill) {
                t.surfaceType = isHill ? SurfaceType.HILLS_DESERT : SurfaceType.MOUNTAINS_DESERT;
            } else {
                t.surfaceType = SurfaceType.DESERT_ROCKY;
            }
            return;
        }

        if (isHill) {
            t.surfaceType = SurfaceType.HILLS;
        } else if (isMountain) {
            t.surfaceType = SurfaceType.MOUNTAINS;
        } else {
            t.surfaceType = SurfaceType.PLAINS;
        }
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double fallbackSeason(double seasonal, double fallback) {
        return Double.isNaN(seasonal) ? fallback : seasonal;
    }

    private double aridityIndex(double p, double e) {
        return (p + 0.2) / (e + 0.2);
    }

    private SeasonChoice pickMostFavorableSeason(
            double tWarm, double tCold, double tInter,
            double pWarm, double pCold, double pInter,
            double eWarm, double eCold, double eInter,
            double mWarm, double mCold, double mInter,
            PlanetTuning.SeasonFavorabilityTuning favor
    ) {
        SeasonChoice inter = new SeasonChoice(0, tInter, pInter, eInter, mInter, aridityIndex(pInter, eInter));
        SeasonChoice warm = new SeasonChoice(1, tWarm, pWarm, eWarm, mWarm, aridityIndex(pWarm, eWarm));
        SeasonChoice cold = new SeasonChoice(2, tCold, pCold, eCold, mCold, aridityIndex(pCold, eCold));

        double sInter = seasonFavorability(inter.temp(), inter.ai(), inter.moisture(), favor);
        double sWarm = seasonFavorability(warm.temp(), warm.ai(), warm.moisture(), favor);
        double sCold = seasonFavorability(cold.temp(), cold.ai(), cold.moisture(), favor);

        SeasonChoice best = inter;
        double bestScore = sInter;
        if (sWarm > bestScore + 1e-9) {
            best = warm;
            bestScore = sWarm;
        }
        if (sCold > bestScore + 1e-9) {
            best = cold;
        }
        return best;
    }

    private double seasonFavorability(double tempC, double ai, double moisture, PlanetTuning.SeasonFavorabilityTuning favor) {
        double tempComfort = 1.0 - Math.abs(tempC - favor.tempComfortCenterC()) / Math.max(1e-6, favor.tempComfortHalfRangeC());
        tempComfort = clamp(tempComfort, -1.0, 1.0);
        double liquidWindow = (tempC > favor.liquidMinC() && tempC < favor.liquidMaxC())
                ? favor.liquidBonus()
                : favor.liquidPenalty();
        double aiScore = clamp(
                (ai - favor.aiOffset()) / Math.max(1e-6, favor.aiScale()),
                favor.aiMin(),
                favor.aiMax()
        );
        double moistScore = clamp(
                (moisture - favor.moistureOffset()) / Math.max(1e-6, favor.moistureScale()),
                favor.moistureMin(),
                favor.moistureMax()
        );
        double frostPenalty = (tempC < favor.frostStartC())
                ? clamp((favor.frostStartC() - tempC) / Math.max(1e-6, favor.frostRangeC()), 0.0, 1.0)
                : 0.0;
        double heatPenalty = (tempC > favor.heatStartC())
                ? clamp((tempC - favor.heatStartC()) / Math.max(1e-6, favor.heatRangeC()), 0.0, 1.0)
                : 0.0;
        return favor.weightTempComfort() * tempComfort
                + favor.weightAi() * aiScore
                + favor.weightMoisture() * moistScore
                + liquidWindow
                - favor.weightFrostPenalty() * frostPenalty
                - favor.weightHeatPenalty() * heatPenalty;
    }

    private record SeasonChoice(int id, double temp, double precip, double evap, double moisture, double ai) {
    }

    private enum ReliefClass {
        LOWLAND,
        HILL,
        MOUNTAIN
    }

    private record BiomeFeatures(
            double temp,
            double precip,
            double evap,
            double soilMoist,
            double ai,
            double tempWarm,
            double tempCold,
            double monsoon,
            boolean riverFed,
            boolean coastal
    ) {}

    private record BiomeProfile(
            SurfaceType out,
            ReliefClass relief,
            double tempCenter,
            double tempTol,
            double aiCenter,
            double aiTol,
            double soilCenter,
            double soilTol,
            double precipCenter,
            double precipTol,
            double monsoonCenter,
            double monsoonTol,
            double minColdSeasonTemp,
            double maxWarmSeasonTemp,
            double riverAffinity,
            double coastalAffinity
    ) {}

    private static final List<BiomeProfile> BIOME_PROFILES = List.of(
            // Lowlands
            p(SurfaceType.RAINFOREST, ReliefClass.LOWLAND, 28, 10, 1.45, 0.85, 82, 26, 24, 18, 0.45, 0.35, 6, 46, 0.12, 0.08),
            p(SurfaceType.PLAINS_FOREST, ReliefClass.LOWLAND, 20, 11, 1.00, 0.60, 58, 24, 12, 12, 0.25, 0.35, -12, 42, 0.08, 0.08),
            p(SurfaceType.SAVANNA, ReliefClass.LOWLAND, 26, 10, 0.70, 0.45, 38, 20, 8, 8, 0.40, 0.35, -8, 46, 0.10, 0.06),
            p(SurfaceType.DRY_SAVANNA, ReliefClass.LOWLAND, 24, 11, 0.45, 0.35, 24, 16, 4, 6, 0.30, 0.35, -12, 48, 0.08, 0.04),
            p(SurfaceType.GRASSLAND, ReliefClass.LOWLAND, 14, 10, 0.62, 0.35, 36, 18, 7, 7, 0.20, 0.40, -18, 40, 0.06, 0.06),
            p(SurfaceType.PLAINS_GRASS, ReliefClass.LOWLAND, 10, 12, 0.72, 0.40, 42, 20, 8, 8, 0.20, 0.40, -22, 36, 0.04, 0.04),
            p(SurfaceType.TUNDRA, ReliefClass.LOWLAND, -1, 11, 0.75, 0.45, 30, 18, 4, 5, 0.18, 0.45, -60, 20, 0.02, 0.02),
            p(SurfaceType.COLD_DESERT, ReliefClass.LOWLAND, 2, 12, 0.28, 0.22, 8, 8, 1.0, 2.5, 0.18, 0.45, -70, 24, -0.02, -0.02),
            p(SurfaceType.DESERT_ROCKY, ReliefClass.LOWLAND, 30, 12, 0.22, 0.22, 10, 10, 1.5, 3.0, 0.15, 0.45, -35, 60, -0.02, -0.02),
            // Hills
            p(SurfaceType.HILLS_RAINFOREST, ReliefClass.HILL, 24, 10, 1.35, 0.75, 72, 24, 18, 14, 0.40, 0.35, 2, 42, 0.10, 0.06),
            p(SurfaceType.HILLS_FOREST, ReliefClass.HILL, 18, 10, 0.95, 0.55, 54, 22, 11, 10, 0.22, 0.35, -15, 38, 0.07, 0.07),
            p(SurfaceType.HILLS_SAVANNA, ReliefClass.HILL, 23, 10, 0.65, 0.40, 35, 18, 7, 7, 0.35, 0.35, -10, 42, 0.08, 0.04),
            p(SurfaceType.HILLS_DRY_SAVANNA, ReliefClass.HILL, 21, 11, 0.45, 0.32, 24, 15, 4, 6, 0.28, 0.35, -14, 46, 0.06, 0.03),
            p(SurfaceType.HILLS_GRASS, ReliefClass.HILL, 12, 10, 0.65, 0.35, 36, 16, 6, 7, 0.20, 0.40, -22, 34, 0.05, 0.04),
            p(SurfaceType.HILLS_TUNDRA, ReliefClass.HILL, -4, 10, 0.70, 0.40, 28, 16, 3, 4, 0.18, 0.45, -65, 16, 0.01, 0.01),
            p(SurfaceType.HILLS_DESERT, ReliefClass.HILL, 27, 12, 0.22, 0.22, 9, 9, 1.2, 2.8, 0.16, 0.45, -35, 58, -0.02, -0.03),
            // Mountains
            p(SurfaceType.MOUNTAINS_RAINFOREST, ReliefClass.MOUNTAIN, 20, 10, 1.20, 0.70, 60, 20, 14, 12, 0.30, 0.35, -5, 36, 0.06, 0.05),
            p(SurfaceType.MOUNTAINS_FOREST, ReliefClass.MOUNTAIN, 12, 10, 0.95, 0.55, 50, 18, 10, 9, 0.20, 0.40, -20, 30, 0.05, 0.05),
            p(SurfaceType.ALPINE_MEADOW, ReliefClass.MOUNTAIN, 8, 8, 0.70, 0.35, 38, 16, 6, 7, 0.20, 0.40, -22, 24, 0.03, 0.03),
            p(SurfaceType.MOUNTAINS_TUNDRA, ReliefClass.MOUNTAIN, -6, 8, 0.75, 0.40, 24, 14, 3, 4, 0.18, 0.45, -80, 14, 0.01, 0.01),
            p(SurfaceType.MOUNTAINS_SNOW, ReliefClass.MOUNTAIN, -16, 8, 0.65, 0.45, 18, 14, 2, 3, 0.15, 0.45, -90, 8, 0.00, 0.00),
            p(SurfaceType.MOUNTAINS, ReliefClass.MOUNTAIN, 10, 12, 0.55, 0.35, 28, 16, 5, 7, 0.20, 0.45, -30, 32, 0.03, 0.03),
            p(SurfaceType.MOUNTAINS_DESERT, ReliefClass.MOUNTAIN, 20, 12, 0.22, 0.22, 8, 8, 1, 3, 0.15, 0.45, -40, 55, -0.03, -0.03)
    );

    private static BiomeProfile p(
            SurfaceType out,
            ReliefClass relief,
            double tempCenter,
            double tempTol,
            double aiCenter,
            double aiTol,
            double soilCenter,
            double soilTol,
            double precipCenter,
            double precipTol,
            double monsoonCenter,
            double monsoonTol,
            double minColdSeasonTemp,
            double maxWarmSeasonTemp,
            double riverAffinity,
            double coastalAffinity
    ) {
        return new BiomeProfile(
                out, relief, tempCenter, tempTol, aiCenter, aiTol, soilCenter, soilTol,
                precipCenter, precipTol, monsoonCenter, monsoonTol, minColdSeasonTemp,
                maxWarmSeasonTemp, riverAffinity, coastalAffinity
        );
    }

    private SurfaceType selectBiomeByProfiles(long seed, Tile tile, ReliefClass relief, BiomeFeatures f) {
        BiomeProfile best = null;
        double bestScore = -1e18;
        for (BiomeProfile p : BIOME_PROFILES) {
            if (p.relief() != relief) continue;
            double s = biomeScore(p, f);
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        if (best == null) return null;
        SurfaceType out = best.out();
        if (out == SurfaceType.DESERT_ROCKY && relief == ReliefClass.LOWLAND) {
            out = pickLowlandDesert(seed, tile);
        }
        if (bestScore < -8.5) return null;
        return out;
    }

    private double biomeScore(BiomeProfile p, BiomeFeatures f) {
        // Cap extreme wet AI tails so tropical humid tiles can still match profile envelopes.
        double aiEff = clamp(f.ai(), 0.0, 3.0);
        double precipEff = Math.min(f.precip(), 30.0);
        double s = 0.0;
        s -= absNorm(f.temp(), p.tempCenter(), p.tempTol());
        s -= absNorm(aiEff, p.aiCenter(), p.aiTol());
        s -= 0.85 * absNorm(f.soilMoist(), p.soilCenter(), p.soilTol());
        s -= 0.55 * absNorm(precipEff, p.precipCenter(), p.precipTol());
        s -= 0.45 * absNorm(f.monsoon(), p.monsoonCenter(), p.monsoonTol());

        if (f.riverFed()) s += p.riverAffinity();
        if (f.coastal()) s += p.coastalAffinity();

        if (f.tempCold() < p.minColdSeasonTemp()) {
            double d = p.minColdSeasonTemp() - f.tempCold();
            s -= clamp(d / 10.0, 0.0, 3.5);
        }
        if (f.tempWarm() > p.maxWarmSeasonTemp()) {
            double d = f.tempWarm() - p.maxWarmSeasonTemp();
            s -= clamp(d / 10.0, 0.0, 3.5);
        }
        return s;
    }

    private double absNorm(double v, double c, double tol) {
        return Math.abs(v - c) / Math.max(1e-6, tol);
    }

    private boolean isHyperArid(
            double precipPhysWarm,
            double precipPhysInter,
            double precipPhysCold,
            double soilWarm,
            double soilInter,
            double soilCold
    ) {
        double pMax = Math.max(precipPhysWarm, Math.max(precipPhysInter, precipPhysCold));
        double soilMax = Math.max(soilWarm, Math.max(soilInter, soilCold));
        // Absolute dryness gate:
        // - no meaningful physical precipitation in any season
        // - and soil moisture remains extremely low even in the best season
        if (pMax < 0.15 && soilMax < 4.0) return true;
        // Extra strict branch for near-zero precipitation everywhere.
        return pMax < 0.05 && soilInter < 6.0 && soilMax < 6.5;
    }

    private BiomeRegime classifyRegime(double tempWarm, double tempCold, double aiAnn, double aiWarm, double aiCold, double monsoon) {
        if (tempWarm < 6.0) return BiomeRegime.CRYO;
        if (aiAnn < 0.38) {
            if (monsoon > 0.35 || Math.abs(aiWarm - aiCold) > 0.35) return BiomeRegime.ARID_SEASONAL;
            return BiomeRegime.ARID_STABLE;
        }
        if (monsoon > 0.45) return BiomeRegime.MONSOONAL;
        if (tempWarm >= 24.0) {
            if (aiAnn >= 1.15) return BiomeRegime.TROPICAL_HUMID;
            return BiomeRegime.TROPICAL_DRYWET;
        }
        if ((tempWarm - tempCold) >= 30.0 && tempCold <= -10.0) return BiomeRegime.BOREAL_CONTINENTAL;
        return BiomeRegime.TEMPERATE_BALANCED;
    }

    private int percentile(int[] arr, double p) {
        if (arr.length == 0) return 0;
        int idx = (int) Math.round((arr.length - 1) * p);
        if (idx < 0) idx = 0;
        if (idx >= arr.length) idx = arr.length - 1;
        return arr[idx];
    }

    private double percentile(double[] arr, double p) {
        if (arr.length == 0) return 0.0;
        double[] copy = arr.clone();
        Arrays.sort(copy);
        int idx = (int) Math.round((copy.length - 1) * p);
        if (idx < 0) idx = 0;
        if (idx >= copy.length) idx = copy.length - 1;
        return copy[idx];
    }

    private double normalizeToUnit(double v, double lo, double hi) {
        if (hi <= lo + 1e-9) return 0.5;
        return clamp((v - lo) / (hi - lo), 0.0, 1.0);
    }

    private boolean isWaterLike(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_DEEP, OPEN_WATER_SHALLOW,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP, STEAM_SEA,
                    COAST_SANDY, COAST_ROCKY -> true;
            default -> false;
        };
    }

    private boolean hasWaterNeighbor(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return false;
        for (Tile nb : t.neighbors) {
            if (nb != null && isWaterLike(nb.surfaceType)) return true;
        }
        return false;
    }

    private SurfaceType pickHillBiome(double tempAdj, double moistAdj) {
        if (tempAdj < -5) return SurfaceType.HILLS_TUNDRA;
        if (moistAdj < -15) return SurfaceType.HILLS_DESERT;
        if (tempAdj > 24) {
            if (moistAdj > 12) return SurfaceType.HILLS_RAINFOREST;
            if (moistAdj > 5) return SurfaceType.HILLS_FOREST;
            if (moistAdj > -8) return SurfaceType.HILLS_SAVANNA;
            return SurfaceType.HILLS_DRY_SAVANNA;
        }
        if (moistAdj > 10) return SurfaceType.HILLS_FOREST;
        if (moistAdj > -4) return SurfaceType.HILLS_GRASS;
        return SurfaceType.HILLS_DRY_SAVANNA;
    }

    private SurfaceType pickMountainBiome(double tempAdj, double moistAdj) {
        if (tempAdj < -15) return SurfaceType.MOUNTAINS_SNOW;
        if (tempAdj < 4) return SurfaceType.MOUNTAINS_TUNDRA;
        if (moistAdj < -15) return SurfaceType.MOUNTAINS_DESERT;
        if (tempAdj > 22 && moistAdj > 12) return SurfaceType.MOUNTAINS_RAINFOREST;
        if (moistAdj > 8) return SurfaceType.MOUNTAINS_FOREST;
        if (tempAdj < 12) return SurfaceType.ALPINE_MEADOW;
        return SurfaceType.MOUNTAINS;
    }

    private double noise01(long seed, int id) {
        long x = seed + id * 0x9E3779B97F4A7C15L;
        x ^= (x >>> 27);
        x *= 0x3C79AC492BA7B653L;
        x ^= (x >>> 33);
        x *= 0x1C69B3F74AC4AE35L;
        x ^= (x >>> 27);
        long v = x & 0xFFFFFFFFL;
        return v / (double) 0xFFFFFFFFL;
    }

    private double octaveNoise(long seed, int id) {
        double n1 = noise01(seed, id) * 2.0 - 1.0;
        double n2 = noise01(seed + 911382L, id * 3) * 2.0 - 1.0;
        return (n1 * 0.7 + n2 * 0.3);
    }

    private double maxSlope(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        int e = t.elevation;
        int max = 0;
        for (Tile n : t.neighbors) {
            int d = Math.abs(e - n.elevation);
            if (d > max) max = d;
        }
        return max;
    }

    private SurfaceType greenerByOneStep(SurfaceType st) {
        return switch (st) {
            case DESERT_SAND, DESERT_ROCKY, ROCKY_DESERT, ROCK_DESERT, COLD_DESERT -> SurfaceType.DRY_SAVANNA;
            case HILLS_DESERT -> SurfaceType.HILLS_DRY_SAVANNA;
            case MOUNTAINS_DESERT -> SurfaceType.MOUNTAINS;

            case DRY_SAVANNA -> SurfaceType.SAVANNA;
            case HILLS_DRY_SAVANNA -> SurfaceType.HILLS_SAVANNA;

            case SAVANNA -> SurfaceType.GRASSLAND;
            case HILLS_SAVANNA -> SurfaceType.HILLS_GRASS;
            case MOUNTAINS -> SurfaceType.MOUNTAINS_FOREST;

            case GRASSLAND, PLAINS_GRASS -> SurfaceType.PLAINS_FOREST;
            case HILLS_GRASS -> SurfaceType.HILLS_FOREST;
            case MOUNTAINS_FOREST -> SurfaceType.MOUNTAINS_RAINFOREST;
            case ALPINE_MEADOW -> SurfaceType.MOUNTAINS_FOREST;

            case PLAINS_FOREST, FOREST -> SurfaceType.RAINFOREST;
            case HILLS_FOREST -> SurfaceType.HILLS_RAINFOREST;

            default -> st;
        };
    }

    private SurfaceType pickLowlandDesert(long seed, Tile t) {
        // Deterministic skew: rocky deserts dominate over sandy deserts.
        double r = noise01(seed ^ 0x5DEECE66DL, t.id * 17 + 11);
        return (r < 0.20) ? SurfaceType.DESERT_SAND : SurfaceType.DESERT_ROCKY;
    }

    private double lifeMaxTemperatureC(PlanetConfig planet) {
        if (planet == null || !planet.hasSurfaceLife) return Double.POSITIVE_INFINITY;
        // Provenance codes:
        // 1 PRIMORDIAL, 2 PRIMORDIAL_DORMANT, 3 SEEDED_RECENT, 4 SEEDED_RECENT_DORMANT, 0 ABIOGENIC.
        // For active biospheres in this pass: seeded has lower heat tolerance.
        return isSeededLife(planet) ? 80.0 : 120.0;
    }

    private boolean isSeededLife(PlanetConfig planet) {
        if (planet == null || !planet.hasSurfaceLife) return false;
        int p = planet.biosphereProvenance;
        return p == 3 || p == 4;
    }

    private boolean tileExceedsLifeThermalLimit(Tile t, PlanetConfig planet, double lifeMaxTempC) {
        double limit = lifeTemperatureLimitC(t, planet, lifeMaxTempC);
        double thermalPeak = Math.max(
                firstFinite(t.biomeTempWarm, t.tempWarm, t.temperature),
                firstFinite(t.biomeTempInterseason, t.temperature, t.tempCold)
        );
        return thermalPeak > limit;
    }

    private double lifeTemperatureLimitC(Tile t, PlanetConfig planet, double lifeMaxTempC) {
        double pBarTile = (t == null) ? 0.0 : Math.max(0.0, t.pressure) / 1000.0;
        double pBarPlanet = (planet == null) ? 0.0 : Math.max(0.0, planet.atmosphereDensity);
        double pBar = Math.max(0.05, Math.max(pBarPlanet, pBarTile));
        double boilC = boilingPointC(pBar);
        return Math.min(lifeMaxTempC, boilC);
    }

    private double boilingPointC(double pBar) {
        double p = Math.max(0.01, pBar);
        double pMmHg = p * 750.062;
        double A = 8.14019;
        double B = 1810.94;
        double C = 244.485;
        return B / (A - Math.log10(pMmHg)) - C;
    }

    private double firstFinite(double... vals) {
        if (vals == null || vals.length == 0) return Double.NaN;
        for (double v : vals) {
            if (!Double.isNaN(v) && Double.isFinite(v)) return v;
        }
        return Double.NaN;
    }

}
