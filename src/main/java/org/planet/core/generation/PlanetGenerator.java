package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;
import org.planet.core.model.SurfaceType;

import java.util.*;

public class PlanetGenerator {

    private final Random random;
    private final GeneratorSettings settings;

    public PlanetGenerator(GeneratorSettings settings) {
        this.settings = settings;
        this.random = new Random(settings.seed);
    }

    /**
     * Основной вход
     */
    public void generateBaseSurface(List<Tile> tiles, PlanetConfig planet) {
        int oceanTarget = targetWaterTiles(tiles.size());

        switch (planet.waterCoverageOrdinal) {
            case 0 -> generateDry(tiles, planet, oceanTarget);           // DRY
            case 1 -> generateLakes(tiles, planet, oceanTarget);         // LAKES
            case 2 -> generateSeas(tiles, planet, oceanTarget);          // SEAS
            case 3 -> generateSingleOcean(tiles, planet, oceanTarget);   // OCEAN
            case 4 -> generateOceans(tiles, planet, oceanTarget, 2);     // OCEANS
            case 5 -> generateOceans(tiles, planet, oceanTarget, 3);     // MANY_OCEANS
            case 6 -> generateArchipelagos(tiles, planet, oceanTarget);  // ARCHIPELAGOS
            case 7 -> generateOceanPlanet(tiles, planet);               // OCEAN_PLANET
            default -> generateSeas(tiles, planet, oceanTarget);
        }
    }


    private void generateDry(List<Tile> tiles, PlanetConfig planet, int oceanTarget) {
        // Базовый сухой режим: без жидкой воды.
        // Варианты редких оазисов/подлёдных водоёмов вводим позже отдельной моделью.
        for (Tile t : tiles) t.surfaceType = SurfaceType.PLAINS;
    }

    private void generateLakes(List<Tile> tiles, PlanetConfig planet, int oceanTarget) {
        for (Tile t : tiles) t.surfaceType = SurfaceType.PLAINS;
        placeLakes(tiles, planet, oceanTarget, 3, 12.0);
    }

    private void generateSeas(List<Tile> tiles, PlanetConfig planet, int oceanTarget) {
        for (Tile t : tiles) t.surfaceType = SurfaceType.PLAINS;
        int basins = clamp(2 + random.nextInt(2), 2, 3);
        growWaterBasins(tiles, planet, oceanTarget, basins, 0);
    }

    private void generateSingleOcean(List<Tile> tiles, PlanetConfig planet, int oceanTarget) {
        for (Tile t : tiles) t.surfaceType = SurfaceType.PLAINS;
        growWaterBasins(tiles, planet, oceanTarget, 1, 0);
    }

    private void generateOceans(List<Tile> tiles, PlanetConfig planet, int oceanTarget, int basins) {
        for (Tile t : tiles) t.surfaceType = SurfaceType.PLAINS;
        growWaterBasins(tiles, planet, oceanTarget, basins, 0);
    }

    private void generateArchipelagos(List<Tile> tiles, PlanetConfig planet, int oceanTarget) {
        // почти всё вода, суша — острова/гряды
        for (Tile t : tiles) t.surfaceType = SurfaceType.OCEAN;
        int landTarget = tiles.size() - oceanTarget;
        placeIslands(tiles, planet, landTarget, 3, 6.0);
    }

    private void generateOceanPlanet(List<Tile> tiles, PlanetConfig planet) {
        for (Tile t : tiles) t.surfaceType = SurfaceType.OCEAN;
        // редкие пики суши
        if (random.nextInt(100) < 40) {
            int landTiles = Math.max(1, tiles.size() / 200);
            placeIslands(tiles, planet, landTiles, 2, 10.0);
        }
    }

    private void placeLakes(List<Tile> tiles,
                            PlanetConfig planet,
                            int waterTiles,
                            int maxLakeSize,
                            double minSeedDistDeg) {
        if (waterTiles <= 0) return;
        Set<Tile> water = new HashSet<>();
        List<Tile> seeds = new ArrayList<>();

        int attempts = 0;
        while (water.size() < waterTiles && attempts < tiles.size() * 5) {
            Tile seed = pickWeighted(tiles, planet, true);
            attempts++;
            if (seed == null) break;
            if (!isFarEnough(seed, seeds, minSeedDistDeg)) continue;

            seeds.add(seed);
            int lakeSize = 1 + random.nextInt(maxLakeSize);
            growFromSeed(seed, lakeSize, water, SurfaceType.OCEAN);
        }
    }

    private void placeIslands(List<Tile> tiles,
                              PlanetConfig planet,
                              int landTiles,
                              int maxIslandSize,
                              double minSeedDistDeg) {
        if (landTiles <= 0) return;
        Set<Tile> land = new HashSet<>();
        List<Tile> seeds = new ArrayList<>();

        int attempts = 0;
        while (land.size() < landTiles && attempts < tiles.size() * 5) {
            Tile seed = pickWeighted(tiles, planet, false);
            attempts++;
            if (seed == null) break;
            if (!isFarEnough(seed, seeds, minSeedDistDeg)) continue;

            seeds.add(seed);
            int islandSize = 1 + random.nextInt(maxIslandSize);
            growFromSeed(seed, islandSize, land, SurfaceType.PLAINS);
        }
    }

    private void growWaterBasins(List<Tile> tiles,
                                 PlanetConfig planet,
                                 int waterTiles,
                                 int basins,
                                 int maxBasinSize) {
        if (waterTiles <= 0) return;
        Set<Tile> water = new HashSet<>();
        List<Tile> seeds = new ArrayList<>();

        int remaining = waterTiles;
        for (int b = 0; b < basins && remaining > 0; b++) {
            int target = Math.max(1, remaining / (basins - b));
            if (maxBasinSize > 0) target = Math.min(target, maxBasinSize);

            Tile seed = pickWeighted(tiles, planet, true);
            if (seed == null) break;
            if (!isFarEnough(seed, seeds, 8.0)) {
                continue;
            }
            seeds.add(seed);

            int added = growFromSeed(seed, target, water, SurfaceType.OCEAN);
            remaining -= added;
        }
    }

    private int growFromSeed(Tile seed,
                             int target,
                             Set<Tile> visited,
                             SurfaceType type) {
        if (visited.contains(seed)) return 0;
        int added = 0;
        List<Tile> frontier = new ArrayList<>();
        frontier.add(seed);

        while (!frontier.isEmpty() && added < target) {
            Tile cur = frontier.remove(random.nextInt(frontier.size()));
            if (visited.contains(cur)) continue;
            visited.add(cur);
            cur.surfaceType = type;
            added++;

            if (cur.neighbors == null) continue;
            for (Tile n : cur.neighbors) {
                if (!visited.contains(n)) {
                    frontier.add(n);
                }
            }
        }
        return added;
    }

    private Tile pickWeighted(List<Tile> tiles, PlanetConfig planet, boolean forWater) {
        double total = 0.0;
        double[] weights = new double[tiles.size()];

        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            double w = climateWeight(t, planet, forWater);
            weights[i] = w;
            total += w;
        }

        if (total <= 0.0) {
            return tiles.get(random.nextInt(tiles.size()));
        }

        double r = random.nextDouble() * total;
        for (int i = 0; i < tiles.size(); i++) {
            r -= weights[i];
            if (r <= 0) return tiles.get(i);
        }
        return tiles.get(tiles.size() - 1);
    }

    private double climateWeight(Tile t, PlanetConfig planet, boolean forWater) {
        double w = 1.0;

        if (planet.tidalLocked) {
            double dist = angularDistanceDeg(t.lat, t.lon, 0.0, 0.0);
            // максимум около терминатора (90 градусов)
            double d = dist - 90.0;
            double sigma = 25.0;
            w = Math.exp(-(d * d) / (2 * sigma * sigma));
        } else {
            double lat = Math.abs(t.lat);
            double hot = planet.meanTemperatureK > 300 ? 1.0 : 0.0;
            double base = 1.0 - (lat / 90.0);
            if (hot > 0.5) {
                base = lat / 90.0; // горячие → предпочитаем высокие широты
            }
            w = 0.3 + 0.7 * base;
        }

        // для суши немного смещаем в сторону противоположных зон
        if (!forWater) {
            w = 1.0 / (0.2 + w);
        }
        return w;
    }

    private boolean isFarEnough(Tile seed, List<Tile> others, double minDistDeg) {
        for (Tile o : others) {
            if (angularDistanceDeg(seed.lat, seed.lon, o.lat, o.lon) < minDistDeg) {
                return false;
            }
        }
        return true;
    }

    private int targetWaterTiles(int total) {
        int ocean = settings.oceanCoverage;
        ocean = Math.max(0, Math.min(100, ocean));
        int halfStep = 5;
        int minOcean = Math.max(0, ocean - halfStep);
        int maxOcean = Math.min(100, ocean + halfStep);
        int oceanTarget = (minOcean == maxOcean)
                ? ocean
                : minOcean + random.nextInt(maxOcean - minOcean + 1);
        return (int) Math.round(total * (oceanTarget / 100.0));
    }

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

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

}
