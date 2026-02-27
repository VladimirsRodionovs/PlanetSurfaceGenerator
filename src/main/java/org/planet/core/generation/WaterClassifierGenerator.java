package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.PlanetConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class WaterClassifierGenerator {

    private final long seed;

    public WaterClassifierGenerator(long seed) {
        this.seed = seed;
    }

    public void apply(List<Tile> tiles, PlanetConfig planet, int[] baseSurfaceType) {
        // collect water tiles (legacy ocean)
        boolean[] isWater = new boolean[tiles.size()];
        List<Integer> waterIds = new ArrayList<>();
        for (Tile t : tiles) {
            if (isBaseWater(t, baseSurfaceType)) {
                isWater[t.id] = true;
                waterIds.add(t.id);
            }
        }
        if (waterIds.isEmpty()) return;

        // Без атмосферы: не допускаем жидкую воду на поверхности
        if (!planet.hasAtmosphere) {
            for (Tile t : tiles) {
                if (t == null) continue;
                if (!isWaterSurface(t.surfaceType) && !isBaseWater(t, baseSurfaceType)) continue;
                if (t.temperature <= 0) {
                    t.surfaceType = SurfaceType.ICE;
                } else {
                    t.surfaceType = fallbackLand(t, baseSurfaceType);
                }
            }
            return;
        }

        int landCount = 0;
        if (baseSurfaceType != null) {
            for (int ord : baseSurfaceType) {
                SurfaceType st = SurfaceType.values()[Math.max(0, Math.min(SurfaceType.values().length - 1, ord))];
                if (st != SurfaceType.OCEAN && st != SurfaceType.ICE_OCEAN) landCount++;
            }
        }
        double landFrac = baseSurfaceType == null ? 0.0 : (landCount / (double) baseSurfaceType.length);

        // connected components
        boolean[] visited = new boolean[tiles.size()];
        List<List<Integer>> comps = new ArrayList<>();
        for (int id : waterIds) {
            if (visited[id]) continue;
            List<Integer> comp = new ArrayList<>();
            Deque<Integer> q = new ArrayDeque<>();
            q.add(id);
            visited[id] = true;
            while (!q.isEmpty()) {
                int cur = q.poll();
                comp.add(cur);
                Tile t = tiles.get(cur);
                if (t.neighbors == null) continue;
                for (Tile n : t.neighbors) {
                    if (n == null) continue;
                    if (!isWater[n.id] || visited[n.id]) continue;
                    visited[n.id] = true;
                    q.add(n.id);
                }
            }
            comps.add(comp);
        }
        if (comps.isEmpty()) return;

        // largest component = open water (ocean)
        List<Integer> oceanComp = comps.get(0);
        for (List<Integer> c : comps) {
            if (c.size() > oceanComp.size()) oceanComp = c;
        }
        Set<Integer> oceanIds = new HashSet<>(oceanComp);

        for (Tile t : tiles) {
            if (!isWater[t.id]) continue;
            boolean shallow = isShallowWater(t, baseSurfaceType, landFrac);
            boolean isOcean = oceanIds.contains(t.id);

            if (t.temperature <= 0.0) {
                if (isOcean || t.surfaceType == SurfaceType.ICE_OCEAN) {
                    t.surfaceType = shallow ? SurfaceType.SEA_ICE_SHALLOW : SurfaceType.SEA_ICE_DEEP;
                } else {
                    // Пока без подлёдных тёплых озёр: внутренние воды ниже нуля считаем заледеневшими.
                    t.surfaceType = (t.temperature < -12.0) ? SurfaceType.ICE_SHEET : SurfaceType.GLACIER;
                }
                continue;
            }

            if (t.surfaceType == SurfaceType.ICE_OCEAN) {
                t.surfaceType = shallow ? SurfaceType.SEA_ICE_SHALLOW : SurfaceType.SEA_ICE_DEEP;
                continue;
            }
            double boil = boilingPointC(planet.atmosphereDensity);
            if (planet.tidalLocked && !isInTerminatorBand(t)) {
                t.surfaceType = fallbackLand(t, baseSurfaceType);
                continue;
            }
            if (t.temperature > boil + 1.5) {
                t.surfaceType = fallbackLand(t, baseSurfaceType);
                continue;
            }
            if (t.temperature >= boil - 5.0) {
                t.surfaceType = SurfaceType.STEAM_SEA;
                continue;
            }
            if (isOcean) {
                t.surfaceType = shallow ? SurfaceType.OPEN_WATER_SHALLOW : SurfaceType.OPEN_WATER_DEEP;
            } else {
                boolean salt = isSaltLake(t, planet);
                if (salt) {
                    if (isAcidLake(t, planet)) {
                        t.surfaceType = SurfaceType.LAKE_ACID;
                    } else if (isBrineLake(t, planet)) {
                        t.surfaceType = SurfaceType.LAKE_BRINE;
                    } else {
                        t.surfaceType = SurfaceType.LAKE_SALT;
                    }
                } else {
                    t.surfaceType = SurfaceType.LAKE_FRESH;
                }
            }
        }

        // classify coasts on land adjacent to water
        for (Tile t : tiles) {
            if (isWaterSurface(t.surfaceType)) continue;
            if (!hasWaterNeighbor(t)) continue;
            if (isNonCoastOverride(t.surfaceType)) continue;
            Random rnd = new Random(seed + t.id * 131L);
            int waterNeighbors = countWaterNeighbors(t);
            double slope = maxSlope(t);
            boolean oceanAdj = hasOceanNeighbor(t);
            double coastChance = oceanAdj ? 0.08 : 0.03;
            if (waterNeighbors >= 3) coastChance *= 1.6;
            if (waterNeighbors == 1) coastChance *= 0.7;
            if (slope >= 6) coastChance *= 0.5;
            if (slope <= 2) coastChance *= 1.3;
            if (rnd.nextDouble() > coastChance) continue;
            boolean sandy = t.rockHardness < 0.45 && slope <= 4;
            t.surfaceType = sandy ? SurfaceType.COAST_SANDY : SurfaceType.COAST_ROCKY;
        }

        // Preserve underwater relief separately and normalize current surface elevation for water tiles.
        applyUnderwaterElevation(tiles);
    }

    private void applyUnderwaterElevation(List<Tile> tiles) {
        // ClimateGenerator applies lapse-rate cooling using elevation where 1 elev ~= 100 m:
        // dT = -0.0065 * 100m = -0.65 C per elevation unit.
        // After converting a tile to water with surface elevation forced to 0, remove that cooling bias.
        final double lapsePerElevation = 0.65;
        for (Tile t : tiles) {
            if (isWaterSurface(t.surfaceType)) {
                if (t.elevation != 0) {
                    int oldElevation = t.elevation;
                    t.underwaterElevation = oldElevation;
                    t.elevation = 0;
                    if (oldElevation > 0) {
                        int tempCorr = (int) Math.round(oldElevation * lapsePerElevation);
                        t.temperature += tempCorr;
                    }
                }
            } else {
                t.underwaterElevation = 0;
            }
        }
    }

    private boolean isShallowWater(Tile t, int[] baseSurfaceType, double landFrac) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (n == null) continue;
            if (!isBaseWater(n, baseSurfaceType)) {
                return true;
            }
        }
        Random rnd = new Random(seed + t.id * 131L);
        boolean boundary = isPlateBoundary(t);
        if (boundary && rnd.nextDouble() < 0.35) return true;
        if (landFrac < 0.02) {
            // океан-планета: мелководье редкое
            if (t.elevation > -3 && rnd.nextDouble() < 0.08) return true;
            return false;
        }
        if (t.elevation > -6 && rnd.nextDouble() < 0.6) return true;
        return false;
    }

    private boolean isSaltLake(Tile t, PlanetConfig planet) {
        double temp = t.temperature;
        double precip = Double.isNaN(t.precipAvg) ? 0.0 : t.precipAvg;
        double saltScore = clamp01((temp - 15.0) / 20.0) * clamp01((40.0 - precip) / 40.0);
        if (planet.waterCoverageOrdinal <= 1) saltScore += 0.1;
        return saltScore > 0.45;
    }

    private boolean isBrineLake(Tile t, PlanetConfig planet) {
        double temp = t.temperature;
        double precip = Double.isNaN(t.precipAvg) ? 0.0 : t.precipAvg;
        return temp > 35 || precip < 25 || planet.waterCoverageOrdinal <= 1;
    }

    private boolean isAcidLake(Tile t, PlanetConfig planet) {
        if (!planet.hasAtmosphere) return false;
        if (planet.hasLife) return false;
        if (planet.o2Pct > 1.0) return false;
        if (planet.atmosphereDensity < 5.0) return false;
        return planet.meanTemperatureK > 330 || planet.greenhouseDeltaK > 30;
    }

    private boolean canHaveLiquidWater(Tile t, PlanetConfig planet) {
        if (planet.atmosphereDensity <= 0.05) return false;
        double temp = t.temperature;
        double boil = boilingPointC(planet.atmosphereDensity);
        if (planet.tidalLocked && !isInTerminatorBand(t)) return false;
        return temp >= 0.0 && temp < (boil - 5.0);
    }

    private boolean isInTerminatorBand(Tile t) {
        double dist = angularDistanceDeg(t.lat, t.lon, 0.0, 0.0);
        return dist >= 65.0 && dist <= 115.0;
    }

    private boolean isWaterSurface(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP,
                    STEAM_SEA -> true;
            default -> false;
        };
    }

    private boolean isLiquidWaterSurface(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP -> true;
            default -> false;
        };
    }

    private boolean hasWaterNeighbor(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (n == null) continue;
            if (isLiquidWaterSurface(n.surfaceType)) return true;
        }
        return false;
    }

    private int countWaterNeighbors(Tile t) {
        if (t.neighbors == null) return 0;
        int count = 0;
        for (Tile n : t.neighbors) {
            if (n == null) continue;
            if (isLiquidWaterSurface(n.surfaceType)) count++;
        }
        return count;
    }

    private boolean hasOceanNeighbor(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (n == null) continue;
            if (isOceanSurface(n.surfaceType)) return true;
        }
        return false;
    }

    private boolean isOceanSurface(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP -> true;
            default -> false;
        };
    }

    private boolean isNonCoastOverride(SurfaceType st) {
        return switch (st) {
            case VOLCANIC, VOLCANIC_FIELD, VOLCANO, ACTIVE_VOLCANO, LAVA_PLAINS, LAVA_ISLANDS, LAVA,
                    ICE, ICE_SHEET, GLACIER, METHANE_ICE, AMMONIA_ICE, CO2_ICE,
                    CRATERED_SURFACE, REGOLITH, SWAMP, MUD_SWAMP -> true;
            default -> false;
        };
    }

    private SurfaceType fallbackLand(Tile t, int[] baseSurfaceType) {
        if (baseSurfaceType == null || t.id < 0 || t.id >= baseSurfaceType.length) {
            return SurfaceType.PLAINS;
        }
        int ord = baseSurfaceType[t.id];
        SurfaceType base = SurfaceType.values()[Math.max(0, Math.min(SurfaceType.values().length - 1, ord))];
        if (!isWaterSurface(base)) return base;

        if (t.neighbors == null || t.neighbors.isEmpty()) {
            return SurfaceType.PLAINS;
        }
        int[] counts = new int[SurfaceType.values().length];
        for (Tile n : t.neighbors) {
            if (n == null) continue;
            if (n.id < 0 || n.id >= baseSurfaceType.length) continue;
            int nOrd = baseSurfaceType[n.id];
            SurfaceType nb = SurfaceType.values()[Math.max(0, Math.min(SurfaceType.values().length - 1, nOrd))];
            if (isWaterSurface(nb)) continue;
            counts[nb.ordinal()]++;
        }
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < counts.length; i++) {
            int c = counts[i];
            if (c > bestCount) {
                bestCount = c;
                best = i;
            }
        }
        if (best >= 0) {
            return SurfaceType.values()[best];
        }
        return SurfaceType.PLAINS;
    }

    private boolean isPlateBoundary(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (n != null && n.plateId != t.plateId) return true;
        }
        return false;
    }

    private boolean isBaseWater(Tile t, int[] baseSurfaceType) {
        if (baseSurfaceType == null || t.id < 0 || t.id >= baseSurfaceType.length) {
            return t.surfaceType == SurfaceType.OCEAN || t.surfaceType == SurfaceType.ICE_OCEAN;
        }
        SurfaceType st = SurfaceType.values()[Math.max(0, Math.min(SurfaceType.values().length - 1, baseSurfaceType[t.id]))];
        return st == SurfaceType.OCEAN || st == SurfaceType.ICE_OCEAN;
    }

    private int maxSlope(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0;
        int max = 0;
        for (Tile n : t.neighbors) {
            int d = Math.abs(t.elevation - n.elevation);
            if (d > max) max = d;
        }
        return max;
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private double boilingPointC(double pBar) {
        double p = Math.max(0.01, pBar);
        double pMmHg = p * 750.062;
        double A = 8.14019;
        double B = 1810.94;
        double C = 244.485;
        return B / (A - Math.log10(pMmHg)) - C;
    }

    private double angularDistanceDeg(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.toDegrees(c);
    }
}
