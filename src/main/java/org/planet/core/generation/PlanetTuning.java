package org.planet.core.generation;

import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;

public class PlanetTuning {

    public record SeasonFavorabilityTuning(
            double tempComfortCenterC,
            double tempComfortHalfRangeC,
            double liquidMinC,
            double liquidMaxC,
            double liquidBonus,
            double liquidPenalty,
            double aiOffset,
            double aiScale,
            double aiMin,
            double aiMax,
            double moistureOffset,
            double moistureScale,
            double moistureMin,
            double moistureMax,
            double frostStartC,
            double frostRangeC,
            double heatStartC,
            double heatRangeC,
            double weightTempComfort,
            double weightAi,
            double weightMoisture,
            double weightFrostPenalty,
            double weightHeatPenalty
    ) {}

    public static SeasonFavorabilityTuning seasonFavorabilityTuning() {
        return new SeasonFavorabilityTuning(
                dprop("planet.biome.favor.temp.centerC", 18.0),
                dprop("planet.biome.favor.temp.halfRangeC", 38.0),
                dprop("planet.biome.favor.liquid.minC", -2.0),
                dprop("planet.biome.favor.liquid.maxC", 38.0),
                dprop("planet.biome.favor.liquid.bonus", 0.30),
                dprop("planet.biome.favor.liquid.penalty", -0.25),
                dprop("planet.biome.favor.ai.offset", 0.45),
                dprop("planet.biome.favor.ai.scale", 1.25),
                dprop("planet.biome.favor.ai.min", -0.60),
                dprop("planet.biome.favor.ai.max", 0.90),
                dprop("planet.biome.favor.moist.offset", 22.0),
                dprop("planet.biome.favor.moist.scale", 68.0),
                dprop("planet.biome.favor.moist.min", -0.40),
                dprop("planet.biome.favor.moist.max", 0.60),
                dprop("planet.biome.favor.frost.startC", -12.0),
                dprop("planet.biome.favor.frost.rangeC", 26.0),
                dprop("planet.biome.favor.heat.startC", 38.0),
                dprop("planet.biome.favor.heat.rangeC", 22.0),
                dprop("planet.biome.favor.weight.temp", 0.70),
                dprop("planet.biome.favor.weight.ai", 0.40),
                dprop("planet.biome.favor.weight.moist", 0.28),
                dprop("planet.biome.favor.weight.frostPenalty", 0.55),
                dprop("planet.biome.favor.weight.heatPenalty", 0.50)
        );
    }

    public static void apply(GeneratorSettings settings, PlanetConfig planet, WorldType type) {
        int oceanCoverage = mapWaterCoverage(planet);
        settings.oceanCoverage = oceanCoverage;

        double landFrac = 1.0 - (oceanCoverage / 100.0);
        int continentBias = clamp((int) Math.round(landFrac * 120.0), 0, 100);
        int islandBias = clamp((int) Math.round(oceanCoverage * 0.6), 0, 100);

        // Для крайне сухих миров уменьшаем острова
        if (oceanCoverage <= 10) {
            islandBias = 0;
        }

        settings.continentBias = continentBias;
        settings.islandBias = islandBias;
    }

    public static int plateCount(PlanetConfig planet, WorldType type) {
        if (type == WorldType.AIRLESS || type == WorldType.ICE_VOLATILE) {
            return 6;
        }
        double m = Math.max(0.1, planet.massEarth);
        int v = (int) Math.round(6 + m * 6.0);
        return clamp(v, 6, 24);
    }

    private static int mapWaterCoverage(PlanetConfig planet) {
        switch (planet.waterCoverageOrdinal) {
            case 0: return 1;    // DRY
            case 1: return 8;    // LAKES (5-10%)
            case 2: return 30;   // SEAS
            case 3: return 50;   // OCEAN
            case 4: return 70;   // OCEANS
            case 5: return 80;   // MANY_OCEANS (не уточняли, поставил между 70 и 95)
            case 6: return 95;   // ARCHIPELAGOS
            case 7: return 100;  // OCEAN_PLANET
            default: return 50;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double dprop(String key, double fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
