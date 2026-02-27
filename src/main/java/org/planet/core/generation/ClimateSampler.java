package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.config.PlanetConfig;

public class ClimateSampler {

    public static void sample(java.util.List<Tile> tiles, PlanetConfig planet) {
        for (Tile t : tiles) {
            // Keep any precomputed diurnal range from WindGenerator.
            // If absent, fall back to single-value annual snapshot.
            if (Double.isNaN(t.tempMin)) {
                t.tempMin = t.temperature;
            } else {
                t.tempMin = Math.min(t.tempMin, t.temperature);
            }
            if (Double.isNaN(t.tempMax)) {
                t.tempMax = t.temperature;
            } else {
                t.tempMax = Math.max(t.tempMax, t.temperature);
            }
            t.windAvg = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
            t.windMax = estimateWindMax(t);

            double precip = derivePrecip(t);
            t.precipAvg = precip;
            if (Double.isNaN(t.evapAvg)) {
                t.evapAvg = estimateEvap(t, planet);
            }

            int sunny = estimateSunnyDays(precip, t, planet);
            t.sunnyDays = sunny;
        }
    }

    private static int estimateSunnyDays(double precip, Tile t, PlanetConfig planet) {
        double moist = Math.max(0.0, Math.min(100.0, precip));
        double sunny = 365.0 * (1.0 - moist / 100.0);
        int v = (int) Math.round(sunny);
        if (v < 0) return 0;
        if (v > 365) return 365;
        return v;
    }

    private static double estimateEvap(Tile t, PlanetConfig planet) {
        double temp = t.temperature;
        double wind = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        double pressure = Math.max(0.2, planet.atmosphereDensity);

        double tempFactor = clamp((temp + 10.0) / 45.0, 0.0, 2.0);
        double windFactor = clamp(0.6 + wind / 40.0, 0.6, 2.0);
        double pressFactor = clamp(1.3 - (pressure - 1.0) * 0.4, 0.5, 1.3);

        boolean water = isWaterSurface(t);
        double soil = Double.isNaN(t.moisture) ? 40.0 : t.moisture;
        double atm = Double.isNaN(t.atmMoist) ? 35.0 : t.atmMoist;

        // влажный воздух снижает испарение (мягче, чтобы не гасить испарение полностью)
        double humidityFactor = clamp(1.0 - (atm / 120.0), 0.2, 1.0);
        // почва влияет, но не обнуляет испарение полностью
        double soilFactor = water ? 1.2 : clamp(0.4 + 0.6 * (soil / 100.0), 0.4, 1.0);

        double evap = tempFactor * windFactor * pressFactor * humidityFactor * soilFactor * 25.0;
        return clamp(evap, 0.0, 100.0);
    }

    private static boolean isWaterSurface(Tile t) {
        return switch (t.surfaceType) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP,
                    STEAM_SEA -> true;
            default -> false;
        };
    }

    private static double estimateWindMax(Tile t) {
        double mean = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        if (mean <= 1e-6) return 0.0;

        double shear = localShearIndex(t);
        double slope = maxSlope(t);
        double thermal = localThermalContrast(t);

        double gustFactor = 1.30
                + 0.30 * clamp01(shear / 12.0)
                + 0.20 * clamp01(slope / 10.0)
                + 0.15 * clamp01(thermal / 18.0);
        gustFactor = clamp(gustFactor, 1.15, 2.20);
        return mean * gustFactor;
    }

    private static double localShearIndex(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (Tile n : t.neighbors) {
            double dvx = t.windX - n.windX;
            double dvy = t.windY - n.windY;
            sum += Math.sqrt(dvx * dvx + dvy * dvy);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double localThermalContrast(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (Tile n : t.neighbors) {
            sum += Math.abs(t.temperature - n.temperature);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double maxSlope(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        int e = t.elevation;
        int max = 0;
        for (Tile n : t.neighbors) {
            int d = Math.abs(e - n.elevation);
            if (d > max) max = d;
        }
        return max;
    }

    private static double derivePrecip(Tile t) {
        if (!Double.isNaN(t.precipAvg)) {
            return clamp(t.precipAvg, 0.0, 100.0);
        }
        double moist = Double.isNaN(t.moisture) ? 0.0 : t.moisture;
        double temp = t.temperature;
        double wind = Math.sqrt(t.windX * t.windX + t.windY * t.windY);

        double tempFactor = clamp((temp + 10.0) / 50.0, 0.2, 1.2);
        double windFactor = clamp(0.6 + wind / 15.0, 0.6, 1.4);
        double precip = moist * tempFactor * windFactor;

        double[] oro = orographicFactors(t);
        double windward = oro[0];
        double leeward = oro[1];
        double shadow = oro[2];
        precip *= (1.0 + 0.35 * windward);
        precip *= (1.0 - 0.40 * leeward);
        precip *= (1.0 - 0.55 * shadow);
        return clamp(precip, 0.0, 100.0);
    }

    private static double[] orographicFactors(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return new double[]{0.0, 0.0, 0.0};
        double mag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        if (mag < 1e-6) return new double[]{0.0, 0.0, 0.0};
        double wx = t.windX / mag;
        double wy = t.windY / mag;
        double windward = 0.0;
        double leeward = 0.0;
        for (Tile n : t.neighbors) {
            double dx = (n.lon - t.lon) * 111.0 * Math.cos(Math.toRadians(t.lat));
            double dy = (n.lat - t.lat) * 111.0;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6) continue;
            double dirx = dx / len;
            double diry = dy / len;
            double dot = wx * dirx + wy * diry;
            int diff = n.elevation - t.elevation;
            if (diff > 0 && dot > 0.2) {
                windward = Math.max(windward, diff / 10.0);
            }
            if (diff > 0 && dot < -0.2) {
                leeward = Math.max(leeward, diff / 10.0);
            }
        }
        double shadow = upwindShadow(t, wx, wy);
        windward = clamp(windward, 0.0, 1.5);
        leeward = clamp(leeward, 0.0, 1.5);
        shadow = clamp(shadow, 0.0, 1.5);
        return new double[]{windward, leeward, shadow};
    }

    private static double upwindShadow(Tile t, double wx, double wy) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double best = 0.0;
        double windMag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        int maxDepth = 3;
        if (windMag > 15.0) maxDepth = 4;
        if (windMag > 35.0) maxDepth = 5;
        if (windMag > 60.0) maxDepth = 6;
        for (Tile n : t.neighbors) {
            double[] dir = dirTo(t, n);
            double dot = wx * dir[0] + wy * dir[1];
            if (dot > -0.2) continue;
            int diff = n.elevation - t.elevation;
            if (diff <= 0) continue;
            best = Math.max(best, diff / 10.0);
            best = Math.max(best, upwindChain(n, wx, wy, t.elevation, maxDepth - 1, 1));
        }
        return best;
    }

    private static double upwindChain(Tile start, double wx, double wy, int baseElev, int depthLeft, int step) {
        if (start.neighbors == null || depthLeft <= 0) return 0.0;
        double best = 0.0;
        double decay = 1.0 / (1.0 + 0.5 * step);
        for (Tile nn : start.neighbors) {
            double[] dir2 = dirTo(start, nn);
            double dot2 = wx * dir2[0] + wy * dir2[1];
            if (dot2 > -0.2) continue;
            int diff2 = nn.elevation - baseElev;
            if (diff2 > 0) {
                best = Math.max(best, (diff2 / 10.0) * decay);
            }
            best = Math.max(best, upwindChain(nn, wx, wy, baseElev, depthLeft - 1, step + 1));
        }
        return best;
    }

    private static double[] dirTo(Tile from, Tile to) {
        double dx = (to.lon - from.lon) * 111.0 * Math.cos(Math.toRadians(from.lat));
        double dy = (to.lat - from.lat) * 111.0;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return new double[]{0.0, 0.0};
        return new double[]{dx / len, dy / len};
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }
}
