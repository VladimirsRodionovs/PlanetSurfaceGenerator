package org.planet.core.db;

import org.planet.core.db.dto.StarSystemObjectRow;
import org.planet.core.model.config.PlanetConfig;

import java.util.Locale;

/**
 * Парсит ObjectDescription (CSV blob) и маппит в PlanetConfig.
 * Порядок полей см. в PData CSV Schema.
 */
public class PlanetConfigMapper {

    // Порог "лавового мира" — можно позже вынести в настройки/таблицу.
    private static final double LAVA_MEAN_TEMP_K = 1000.0;
    private static final double LAVA_MAX_TEMP_K = 1200.0;

    public static PlanetConfig fromDescription(StarSystemObjectRow row) {
        if (row == null) {
            throw new IllegalArgumentException("row is null");
        }
        String desc = row.objectDescription;
        if (desc == null || desc.isBlank()) {
            throw new IllegalStateException("ObjectDescription is empty for ObjectInternalID=" + row.objectInternalId);
        }

        String[] p = splitCsv(desc);
        if (p.length < 38) {
            throw new IllegalStateException("ObjectDescription has " + p.length + " fields, expected >= 38");
        }

        PlanetConfig planet = new PlanetConfig();

        // индексы (0-based)
        double sG = parseDouble(p[5]);
        double pBar = parseDouble(p[10]);
        int atmType = parseInt(p[11]);
        double teqK = parseDouble(p[16]);
        double gsDK = parseDouble(p[17]);
        double tMeanK = parseDouble(p[18]);
        double tMaxK = parseDouble(p[20]);
        double tMinK = parseDouble(p[19]);
        int tLock = parseInt(p[21]);
        double mE = parseDouble(p[1]);
        double fracIron = parseDouble(p[6]);
        double fracRock = parseDouble(p[7]);
        double fracIce = parseDouble(p[8]);
        int wCov = parseInt(p[32]);
        double wGel = parseDouble(p[33]);
        double o2Pct = parseDouble(p[34]);
        int biProv = parseInt(p[24]);
        int biSur = parseInt(p[25]);
        int biMic = parseInt(p[26]);
        int biSub = parseInt(p[27]);
        int heavHyd = parseInt(p[28]);
        int lghtHyd = parseInt(p[29]);
        double rE = parseDouble(p[4]);
        String tidHeat = p[22] != null ? p[22].trim().toUpperCase(Locale.ROOT) : "";
        String pRes = p[37] != null ? p[37].trim() : "";

        planet.gravity = sG;
        planet.atmosphereDensity = pBar; // как просили: без клампа
        planet.hasAtmosphere = (atmType != 0) && (pBar > 0.0);
        planet.radiusKm = rE * 6371.0;

        // Температура (часть источников может быть в °C, если значение < 0)
        double meanK = normalizeKelvin(tMeanK);
        double minK = normalizeKelvin(tMinK);
        double maxK = normalizeKelvin(tMaxK);
        planet.meanTemperatureK = meanK;
        planet.meanTemperature = meanK - 273.15;
        planet.minTemperatureK = minK;
        planet.maxTemperatureK = maxK;
        planet.equilibriumTemperatureK = teqK;
        planet.greenhouseDeltaK = gsDK;

        // Приливный захват
        // Для лун считаем приливный захват включённым (иначе нужно явное значение периода вращения)
        planet.tidalLocked = (tLock == 1) || (row.objectPlanetType == 4);

        // Вулканизм: производная от приливного захвата и уровня tidHeat
        int volcanism = planet.tidalLocked ? 40 : 5;
        if ("WEAK".equals(tidHeat)) volcanism = Math.max(volcanism, 50);
        else if ("STRONG".equals(tidHeat)) volcanism = Math.max(volcanism, 80);
        planet.volcanism = volcanism;

        // Лавовый мир по температуре
        planet.lavaWorld = (tMeanK >= LAVA_MEAN_TEMP_K) || (tMaxK >= LAVA_MAX_TEMP_K);

        // PRes: metal,silicates,water_ice,methane_ice,ammonia_ice,organics
        parseResources(pRes, planet);

        planet.massEarth = mE;
        planet.waterCoverageOrdinal = wCov;
        planet.waterGelKm = wGel;
        planet.o2Pct = o2Pct;
        planet.fracIron = fracIron;
        planet.fracRock = fracRock;
        planet.fracIce = fracIce;
        planet.biosphereProvenance = biProv;
        planet.biosphereSurfaceStatus = biSur;
        planet.biosphereMicrobialStatus = biMic;
        planet.biosphereSubsurfaceStatus = biSub;
        // Surface biomes only for active surface biospheres.
        // 1 = PRIMORDIAL, 3 = SEEDED_RECENT; 2/4 are dormant.
        planet.hasSurfaceLife = (biSur != 0) && (biProv == 1 || biProv == 3);
        planet.hasLife = (biSur != 0) || (biMic != 0) || (biSub != 0);
        planet.heavyHydrocarbons = heavHyd == 1;
        planet.lightHydrocarbons = lghtHyd == 1;

        planet.orbitalMeanMotionPerDay = row.orbitMeanMotionPerDay;
        if (row.orbitMeanMotionPerDay > 0.0) {
            planet.orbitalPeriodDays = 360.0 / row.orbitMeanMotionPerDay;
        }

        if (row.axialTiltDeg > 0.0) {
            planet.axialTilt = row.axialTiltDeg;
        } else {
            planet.axialTilt = 0.0;
        }

        // если mean temp отсутствует, попробуем собрать из teq + greenhouse
        if (planet.meanTemperatureK <= 0 && (teqK > 0 || gsDK != 0)) {
            double mk = teqK + gsDK;
            planet.meanTemperatureK = mk;
            planet.meanTemperature = mk - 273.15;
        }

        // Период вращения:
        // - если есть значение из БД — используем его
        // - если луна и приливной захват — 24ч
        // - если не приливная планета — 24ч
        // - иначе 0 (не используется при tidalLocked=true)
        if (row.rotationPeriodHours > 0.0) {
            planet.rotationPeriodHours = row.rotationPeriodHours;
        } else if (row.objectPlanetType == 4 && planet.tidalLocked) {
            planet.rotationPeriodHours = 24.0;
        } else if (!planet.tidalLocked && row.objectPlanetType != 4) {
            planet.rotationPeriodHours = 24.0;
        } else {
            planet.rotationPeriodHours = 0.0;
        }

        planet.rotationSpeed = row.rotationSpeed;
        planet.rotationPrograde = (row.rotationPrograde == 0) ? 0 : 1;

        return planet;
    }

    private static String[] splitCsv(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String[] raw = trimmed.split(",", 38);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].trim().replace("\"", "");
        }
        return raw;
    }

    private static double parseDouble(String v) {
        if (v == null) return 0.0;
        String t = v.trim();
        if (t.isEmpty()) return 0.0;
        return Double.parseDouble(t);
    }

    private static double normalizeKelvin(double v) {
        if (v < 0.0 && v > -273.15) {
            return v + 273.15; // трактуем как °C
        }
        return v;
    }

    private static int parseInt(String v) {
        if (v == null) return 0;
        String t = v.trim();
        if (t.isEmpty()) return 0;
        return Integer.parseInt(t);
    }

    private static void parseResources(String pRes, PlanetConfig planet) {
        if (pRes == null || pRes.isBlank()) return;
        String[] parts = pRes.split(",", -1);
        if (parts.length < 6) return;
        planet.methaneIceFrac = parseDouble(parts[3]);
        planet.ammoniaIceFrac = parseDouble(parts[4]);
        planet.organicsFrac = parseDouble(parts[5]);
    }
}
