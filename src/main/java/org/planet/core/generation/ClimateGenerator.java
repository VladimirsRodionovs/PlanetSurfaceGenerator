package org.planet.core.generation;

import org.planet.core.model.*;
import java.util.List;
import org.planet.core.model.config.PlanetConfig;

public class ClimateGenerator {

    // коэффициенты — потом вынесем в конфиг
    private static final double LATITUDE_TEMP_FACTOR = 0.6;   // °C на градус
    private static final double HEIGHT_LAPSE_RATE = 0.0065;   // °C на метр (упрощённо)
    private static final double VOLCANIC_HEAT = 0.03;         // °C на пункт вулканизма
    private static final double PRESSURE_DAMP_K = 0.25;       // как давление сглаживает широтный градиент

    public void generate(List<Tile> tiles, PlanetConfig planet) {

        if (!planet.hasAtmosphere) {
            applyNoAtmosphere(tiles, planet, 0.0);
            return;
        }

        if (planet.tidalLocked) {
            applyTidalLockedClimate(tiles, planet, 0.0);
        } else {
            applyRotatingClimate(tiles, planet, 0.0);
        }
    }

    public void generateSeason(List<Tile> tiles, PlanetConfig planet, double seasonalShiftLat) {
        if (!planet.hasAtmosphere) {
            applyNoAtmosphere(tiles, planet, seasonalShiftLat);
            return;
        }
        if (planet.tidalLocked) {
            applyTidalLockedClimate(tiles, planet, seasonalShiftLat);
        } else {
            applyRotatingClimate(tiles, planet, seasonalShiftLat);
        }
    }

    // ---------------------------
    // ВРАЩАЮЩАЯСЯ ПЛАНЕТА
    // ---------------------------
    private void applyRotatingClimate(List<Tile> tiles, PlanetConfig planet, double seasonalShiftLat) {

        double baseK = baseTemperatureK(planet, true);
        double baseC = baseK - 273.15;

        for (Tile t : tiles) {

            double temp = baseC;

            // широтная зональность (усиливается при низком давлении)
            double pressureDamp = pressureDampFactor(planet);
            double latSeason = t.lat - seasonalShiftLat;
            temp -= Math.abs(latSeason) * LATITUDE_TEMP_FACTOR * pressureDamp;

            // высота (height = 1 → 100 м)
            double meters = t.elevation * 100.0;
            temp -= meters * HEIGHT_LAPSE_RATE;

            // вулканизм
            temp += t.volcanism * VOLCANIC_HEAT;

            t.temperature = (int) Math.round(temp);

            // давление
            //pressure = 1000  → 1 атмосфера
            //pressure = 0     → вакуум
            //pressure = 2000  → 2 атмосферы
            int basePressure = (int) (planet.atmosphereDensity * 1000);
            int heightPenalty = (int) (meters * 0.12);

            t.pressure = Math.max(0, basePressure - heightPenalty);
        }
        System.out.println("Climate module: Rotating option");
    }

    // ---------------------------
    // ПРИЛИВНО ЗАХВАЧЕННАЯ
    // ---------------------------
    private void applyTidalLockedClimate(List<Tile> tiles, PlanetConfig planet, double seasonalShiftLat) {

        double baseK = baseTemperatureK(planet, true);
        double baseC = baseK - 273.15;

        // подсолнечная точка условно: lat=0, lon=0
        for (Tile t : tiles) {

            double angularDistance = angularDistanceDeg(
                    t.lat, t.lon,
                    seasonalShiftLat, 0.0
            );

            // cos(0)=1 (день), cos(180)=-1 (ночь)
            double sunFactor = Math.cos(Math.toRadians(angularDistance));

            double pressureDamp = pressureDampFactor(planet);
            double temp =
                    baseC +
                            sunFactor * 60.0 * pressureDamp; // амплитуда нагрева

            // высота
            double meters = t.elevation * 100.0;

            temp -= meters * HEIGHT_LAPSE_RATE;

            // вулканизм
            temp += t.volcanism * VOLCANIC_HEAT;

            t.temperature = (int) Math.round(temp);

            // давление
            int basePressure = (int) (planet.atmosphereDensity * 1000);

            // ночь → холод → плотный воздух
            int thermalEffect = (int) (-sunFactor * 120);

            t.pressure = Math.max(
                    0,
                    basePressure + thermalEffect - (int)(meters * 0.1)
            );
        }

        System.out.println("Climate module: Tidal locked option");
    }

    // ---------------------------
    // БЕЗ АТМОСФЕРЫ
    // ---------------------------
    private void applyNoAtmosphere(List<Tile> tiles, PlanetConfig planet, double centerLat) {

        double baseK = baseTemperatureK(planet, false);
        double baseC = baseK - 273.15;

        for (Tile t : tiles) {
            double temp = baseC;

            if (planet.tidalLocked) {
                double dist = angularDistanceDeg(
                        t.lat, t.lon,
                        centerLat, 0.0
                );
                temp += Math.cos(Math.toRadians(dist)) * 120;
            } else {
                temp -= Math.abs(t.lat - centerLat) * 1.2;
            }

            t.temperature = (int) Math.round(temp);
            t.pressure = 0;
        }
        System.out.println("Climate module: No atmosphere option");
    }

    private double baseTemperatureK(PlanetConfig planet, boolean withGreenhouse) {
        double base = planet.meanTemperatureK;
        if (base <= 0.0) {
            double tMin = planet.minTemperatureK;
            double tMax = planet.maxTemperatureK;
            if (tMin > 0.0 || tMax > 0.0) {
                if (tMin <= 0.0) tMin = tMax;
                if (tMax <= 0.0) tMax = tMin;
                base = (tMin + tMax) * 0.5;
            }
        }
        if (base <= 0.0) {
            double teq = planet.equilibriumTemperatureK;
            double gh = withGreenhouse ? planet.greenhouseDeltaK : 0.0;
            base = teq + gh;
        }
        if (base <= 0.0) {
            if (planet.meanTemperature != 0.0) {
                base = planet.meanTemperature + 273.15;
            }
        }
        if (base <= 0.0) {
            base = 273.15;
        }
        return base;
    }

    private double pressureDampFactor(PlanetConfig planet) {
        double p = Math.max(0.01, planet.atmosphereDensity);
        // p=1 -> 1.0, p<1 -> >1 (больше амплитуда), p>1 -> <1 (сглаживание)
        return 1.0 / (1.0 + PRESSURE_DAMP_K * (p - 1.0));
    }

    // ---------------------------
    // ВСПОМОГАТЕЛЬНОЕ
    // ---------------------------
    private double angularDistanceDeg(
            double lat1, double lon1,
            double lat2, double lon2
    ) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                        Math.cos(Math.toRadians(lat1)) *
                                Math.cos(Math.toRadians(lat2)) *
                                Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.toDegrees(c);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
