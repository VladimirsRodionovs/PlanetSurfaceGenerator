package org.planet.core.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.planet.core.generation.ResourcePresence;
import org.planet.core.model.BiomeRegime;
import org.planet.core.model.Tile;
import org.planet.core.model.config.PlanetConfig;

import java.util.List;

/**
 * Compact surface serialization for DB storage in PlanetsSurfaces2 (gzipped JSON payload).
 * Short-key schema:
 * sv = schemaVersion
 * gv = generatorVersion
 * p  = planet meta
 * h  = hexes
 *
 * p keys:
 * si = subsurfaceIceThicknessMeters
 * tc = tileCount
 *
 * h item format:
 * [id, type, prefSeason, bReg, bMod, elevation,
 *  "tMinS|tMinI|tMinW", "tMaxS|tMaxI|tMaxW",
 *  "precipS|precipI|precipW", "soilS|soilI|soilW", "evapS|evapI|evapW",
 *  "windS|windI|windW", "sunS|sunI|sunW",
 *  river, resources, tide, solar, ownerId, neighbors]
 */
public class PlanetSurfaceSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String toJson(List<Tile> tiles, PlanetConfig planet) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("sv", 2);
        root.put("gv", "2026-02-17");

        ObjectNode meta = root.putObject("p");
        meta.put("si", planet.subsurfaceIceThicknessMeters);
        meta.put("tc", tiles.size());

        ArrayNode hexes = root.putArray("h");
        for (Tile t : tiles) {
            ArrayNode hex = MAPPER.createArrayNode();

            int type = (t.surfaceType != null) ? t.surfaceType.ordinal() : 0;
            int regimeIdx = (t.biomeRegime == null) ? BiomeRegime.UNKNOWN.ordinal() : t.biomeRegime.ordinal();
            hex.add(t.id);
            hex.add(type);
            hex.add(t.biomePreferredSeason);
            hex.add(regimeIdx);
            hex.add(t.biomeModifierMask);
            hex.add(t.elevation);

            hex.add(toTripleStr(pickTempMinWarm(t), pickTempMinInter(t, planet), pickTempMinCold(t), 1));
            hex.add(toTripleStr(pickTempMaxWarm(t), pickTempMaxInter(t, planet), pickTempMaxCold(t), 1));
            hex.add(toTripleStr(pickPrecipWarmPhysical(t), pickPrecipInterPhysical(t), pickPrecipColdPhysical(t), 2));
            hex.add(toTripleStr(pickSoilWarm(t), pickSoilInter(t), pickSoilCold(t), 2));
            hex.add(toTripleStr(pickEvapWarmPhysical(t), pickEvapInterPhysical(t), pickEvapColdPhysical(t), 2));
            hex.add(toTripleStr(pickWindWarm(t), pickWindInter(t), pickWindCold(t), 2));
            hex.add(toTripleIntStr(pickSunnyWarm(t), pickSunnyInter(t), pickSunnyCold(t)));

            hex.add(buildRiverBlock(t));
            hex.add(buildResourcesBlock(t));
            hex.add(buildTideBlock(t));
            hex.add(buildSolarBlock(t));

            hex.add(0); // ownerId

            ArrayNode neigh = MAPPER.createArrayNode();
            if (t.neighbors != null) {
                for (Tile n : t.neighbors) neigh.add(n.id);
            }
            hex.add(neigh);

            hexes.add(hex);
        }

        return MAPPER.writeValueAsString(root);
    }

    private static ArrayNode buildResourcesBlock(Tile t) {
        ArrayNode res = MAPPER.createArrayNode();
        if (t.resources != null && !t.resources.isEmpty()) {
            for (ResourcePresence rp : t.resources) {
                ArrayNode r = MAPPER.createArrayNode();
                r.add(rp.type.id);
                r.add(rp.layer.ordinal());
                r.add(rp.quality);
                r.add(rp.saturation);
                r.add(round2(rp.tonnes));
                res.add(r);
            }
        } else if (t.resourceType != 0 || t.resSurface > 0 || t.resMid > 0 || t.resDeep > 0) {
            ArrayNode r = MAPPER.createArrayNode();
            r.add(t.resourceType);
            r.add(0);
            r.add(50);
            r.add(50);
            r.add(round2(t.resSurface));
            res.add(r);
        }
        return res;
    }

    private static ArrayNode buildRiverBlock(Tile t) {
        ArrayNode river = MAPPER.createArrayNode();
        river.add(t.riverType);
        river.add(t.riverTo);
        river.add(round2(t.riverDischargeKgS));
        if (t.riverFrom != null) {
            for (Integer src : t.riverFrom) river.add(src);
        }
        return river;
    }

    private static String buildTideBlock(Tile t) {
        return round2(nan0(t.tidalRangeM)) + "|" + round2(nan0(t.tidalPeriodHours));
    }

    private static String buildSolarBlock(Tile t) {
        return toTripleStr(t.solarKwhDayWarm, t.solarKwhDayInter, t.solarKwhDayCold, 3);
    }

    private static String toTripleStr(double a, double b, double c, int digits) {
        return round(a, digits) + "|" + round(b, digits) + "|" + round(c, digits);
    }

    private static String toTripleIntStr(int a, int b, int c) {
        return a + "|" + b + "|" + c;
    }

    private static double pickInterTemp(Tile t) {
        if (!Double.isNaN(t.biomeTempInterseason)) return t.biomeTempInterseason;
        return t.temperature;
    }

    private static double pickTempWarm(Tile t) {
        if (!Double.isNaN(t.biomeTempWarm)) return t.biomeTempWarm;
        if (!Double.isNaN(t.tempWarm)) return t.tempWarm;
        return pickInterTemp(t);
    }

    private static double pickTempCold(Tile t) {
        if (!Double.isNaN(t.biomeTempCold)) return t.biomeTempCold;
        if (!Double.isNaN(t.tempCold)) return t.tempCold;
        return pickInterTemp(t);
    }

    private static double pickTempMinInter(Tile t, PlanetConfig planet) {
        if (!Double.isNaN(t.tempMinInterseason)) return t.tempMinInterseason;
        if (!Double.isNaN(t.tempMin)) return t.tempMin;
        return estimateSeasonRange(t, planet).minC;
    }

    private static double pickTempMaxInter(Tile t, PlanetConfig planet) {
        if (!Double.isNaN(t.tempMaxInterseason)) return t.tempMaxInterseason;
        if (!Double.isNaN(t.tempMax)) return t.tempMax;
        return estimateSeasonRange(t, planet).maxC;
    }

    private static double pickTempMinWarm(Tile t) {
        if (!Double.isNaN(t.tempMinWarm)) return t.tempMinWarm;
        return !Double.isNaN(t.tempMin) ? t.tempMin : pickTempWarm(t);
    }

    private static double pickTempMinCold(Tile t) {
        if (!Double.isNaN(t.tempMinCold)) return t.tempMinCold;
        return !Double.isNaN(t.tempMin) ? t.tempMin : pickTempCold(t);
    }

    private static double pickTempMaxWarm(Tile t) {
        if (!Double.isNaN(t.tempMaxWarm)) return t.tempMaxWarm;
        return !Double.isNaN(t.tempMax) ? t.tempMax : pickTempWarm(t);
    }

    private static double pickTempMaxCold(Tile t) {
        if (!Double.isNaN(t.tempMaxCold)) return t.tempMaxCold;
        return !Double.isNaN(t.tempMax) ? t.tempMax : pickTempCold(t);
    }

    private static double pickPrecipInterPhysical(Tile t) {
        if (!Double.isNaN(t.precipKgM2DayInterseason)) return t.precipKgM2DayInterseason;
        if (!Double.isNaN(t.precipKgM2Day)) return t.precipKgM2Day;
        if (!Double.isNaN(t.precipAvg)) return t.precipAvg;
        return 0.0;
    }

    private static double pickPrecipWarmPhysical(Tile t) {
        if (!Double.isNaN(t.precipKgM2DayWarm)) return t.precipKgM2DayWarm;
        if (!Double.isNaN(t.precipWarm)) return t.precipWarm;
        return pickPrecipInterPhysical(t);
    }

    private static double pickPrecipColdPhysical(Tile t) {
        if (!Double.isNaN(t.precipKgM2DayCold)) return t.precipKgM2DayCold;
        if (!Double.isNaN(t.precipCold)) return t.precipCold;
        return pickPrecipInterPhysical(t);
    }

    private static double pickEvapInterPhysical(Tile t) {
        if (!Double.isNaN(t.evapKgM2DayInterseason)) return t.evapKgM2DayInterseason;
        if (!Double.isNaN(t.evapKgM2Day)) return t.evapKgM2Day;
        if (!Double.isNaN(t.evapAvg)) return t.evapAvg;
        return 0.0;
    }

    private static double pickEvapWarmPhysical(Tile t) {
        if (!Double.isNaN(t.evapKgM2DayWarm)) return t.evapKgM2DayWarm;
        if (!Double.isNaN(t.evapWarm)) return t.evapWarm;
        return pickEvapInterPhysical(t);
    }

    private static double pickEvapColdPhysical(Tile t) {
        if (!Double.isNaN(t.evapKgM2DayCold)) return t.evapKgM2DayCold;
        if (!Double.isNaN(t.evapCold)) return t.evapCold;
        return pickEvapInterPhysical(t);
    }

    private static double pickSoilInter(Tile t) {
        if (!Double.isNaN(t.biomeMoistureInterseason)) return t.biomeMoistureInterseason;
        if (!Double.isNaN(t.moisture)) return t.moisture;
        return 0.0;
    }

    private static double pickSoilWarm(Tile t) {
        if (!Double.isNaN(t.biomeMoistureWarm)) return t.biomeMoistureWarm;
        if (!Double.isNaN(t.moistureWarm)) return t.moistureWarm;
        return pickSoilInter(t);
    }

    private static double pickSoilCold(Tile t) {
        if (!Double.isNaN(t.biomeMoistureCold)) return t.biomeMoistureCold;
        if (!Double.isNaN(t.moistureCold)) return t.moistureCold;
        return pickSoilInter(t);
    }

    private static double pickWindInter(Tile t) {
        if (!Double.isNaN(t.windAvg)) return t.windAvg;
        return Math.sqrt(t.windX * t.windX + t.windY * t.windY);
    }

    private static double pickWindWarm(Tile t) {
        if (!Double.isNaN(t.windWarm)) return t.windWarm;
        if (!Double.isNaN(t.windXWarm) && !Double.isNaN(t.windYWarm)) {
            return Math.sqrt(t.windXWarm * t.windXWarm + t.windYWarm * t.windYWarm);
        }
        return pickWindInter(t);
    }

    private static double pickWindCold(Tile t) {
        if (!Double.isNaN(t.windCold)) return t.windCold;
        if (!Double.isNaN(t.windXCold) && !Double.isNaN(t.windYCold)) {
            return Math.sqrt(t.windXCold * t.windXCold + t.windYCold * t.windYCold);
        }
        return pickWindInter(t);
    }

    private static int pickSunnyInter(Tile t) {
        if (t.sunnyDays > 0) return t.sunnyDays;
        return estimateSunnyDays(pickPrecipInterPhysical(t));
    }

    private static int pickSunnyWarm(Tile t) {
        if (t.sunnyWarm > 0) return t.sunnyWarm;
        return pickSunnyInter(t);
    }

    private static int pickSunnyCold(Tile t) {
        if (t.sunnyCold > 0) return t.sunnyCold;
        return pickSunnyInter(t);
    }

    private static SeasonRange estimateSeasonRange(Tile t, PlanetConfig planet) {
        double base = t.temperature;
        double latFactor = 1.0 + (Math.abs(t.lat) / 90.0) * 0.6;
        double atm = planet.atmosphereDensity;
        double atmFactor = 1.0 / Math.sqrt(Math.max(0.1, atm));
        if (!planet.hasAtmosphere) atmFactor *= 1.6;
        double delta = 10.0 * latFactor * atmFactor;
        return new SeasonRange(base - delta, base + delta);
    }

    private static int estimateSunnyDays(double precip) {
        double moist = Math.max(0.0, Math.min(100.0, precip));
        double sunny = 365.0 * (1.0 - moist / 100.0);
        int v = (int) Math.round(sunny);
        if (v < 0) return 0;
        if (v > 365) return 365;
        return v;
    }

    private static double nan0(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    private static double round(double v, int digits) {
        double x = nan0(v);
        double p = Math.pow(10.0, digits);
        return Math.round(x * p) / p;
    }

    private static double round2(double v) {
        return Math.round(nan0(v) * 100.0) / 100.0;
    }

    private record SeasonRange(double minC, double maxC) {
    }
}
