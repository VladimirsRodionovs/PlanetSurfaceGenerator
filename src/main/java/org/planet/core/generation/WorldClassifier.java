package org.planet.core.generation;

import org.planet.core.model.config.PlanetConfig;

public class WorldClassifier {

    private static final double AIRLESS_PBAR = 0.05;
    private static final double ICE_MAX_K = 273.0;
    private static final double VOLATILE_MEAN_K = 180.0;
    private static final double VOLATILE_ICE_THRESHOLD = 0.10;

    public static WorldType classify(PlanetConfig planet) {
        if (planet.lavaWorld) return WorldType.LAVA_WORLD;

        if (!planet.hasAtmosphere || planet.atmosphereDensity < AIRLESS_PBAR) {
            return WorldType.AIRLESS;
        }

        boolean iceTemp = planet.maxTemperatureK > 0 && planet.maxTemperatureK < ICE_MAX_K;
        boolean volatileRich = (planet.methaneIceFrac + planet.ammoniaIceFrac) >= VOLATILE_ICE_THRESHOLD;

        if (iceTemp) {
            if ((planet.meanTemperatureK > 0 && planet.meanTemperatureK < VOLATILE_MEAN_K) || volatileRich) {
                return WorldType.ICE_VOLATILE;
            }
            return WorldType.ICE_ROCKY;
        }

        return WorldType.ROCKY_TECTONIC;
    }

    public static StageProfile profileFor(WorldType type, PlanetConfig planet) {
        boolean wantsRecalc = planet.hasAtmosphere && planet.waterCoverageOrdinal >= 2;
        if (planet.hasLife) {
            wantsRecalc = true;
        }

        return switch (type) {
            case AIRLESS -> StageProfile.airless();
            case ICE_VOLATILE -> StageProfile.iceWorld(wantsRecalc);
            case ICE_ROCKY -> wantsRecalc ? StageProfile.upToErosionWithRecalc() : StageProfile.upToErosion();
            case LAVA_WORLD -> StageProfile.lavaWorld();
            case ROCKY_TECTONIC -> wantsRecalc ? StageProfile.upToErosionWithRecalc() : StageProfile.upToErosion();
        };
    }
}
