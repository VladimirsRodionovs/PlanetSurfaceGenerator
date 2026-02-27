package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.RiverBaseType;
import org.planet.core.model.config.GeneratorSettings;

import java.util.List;

public class ReliefClassifierGenerator {

    public void apply(List<Tile> tiles, GeneratorSettings settings) {
        int[] elev = tiles.stream()
                .filter(t -> !isExcluded(t.surfaceType))
                .mapToInt(t -> t.elevation)
                .sorted()
                .toArray();
        if (elev.length == 0) return;

        int p10 = percentile(elev, 0.10);
        int p85 = percentile(elev, 0.85);

        for (Tile t : tiles) {
            if (isExcluded(t.surfaceType)) continue;

            // Каньоны: только по рекам и глубине
            if (t.canyonDepth >= 2
                    || t.riverBaseType == RiverBaseType.CANYON
                    || t.riverBaseType == RiverBaseType.WATERFALL) {
                t.surfaceType = classifyCanyon(t.surfaceType);
                continue;
            }

            double slope = maxSlope(t);

            // Гребни: самые высокие и резкие участки
            if (isRidgeCandidate(t, settings, p85, slope)) {
                t.surfaceType = classifyRidge(t.surfaceType);
                continue;
            }

            // Дно бассейнов: низины с малыми уклонами
            if (isBasinCandidate(t, p10, slope)) {
                t.surfaceType = classifyBasin(t);
            }
        }
    }

    private boolean isExcluded(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP,
                    STEAM_SEA,
                    COAST_SANDY, COAST_ROCKY,
                    ICE, ICE_SHEET, GLACIER,
                    VOLCANIC, VOLCANIC_FIELD, VOLCANO, ACTIVE_VOLCANO, LAVA_PLAINS, LAVA_ISLANDS, LAVA,
                    REGOLITH, CRATERED_SURFACE,
                    METHANE_ICE, AMMONIA_ICE, CO2_ICE,
                    CANYON, RIDGE, BASIN_FLOOR,
                    RIDGE_ROCK, RIDGE_SNOW, RIDGE_TUNDRA, RIDGE_GRASS, RIDGE_FOREST, RIDGE_DESERT,
                    CANYON_ROCK, CANYON_TUNDRA, CANYON_GRASS, CANYON_FOREST, CANYON_DESERT,
                    BASIN_GRASS, BASIN_FOREST, BASIN_DRY, BASIN_SWAMP, BASIN_TUNDRA -> true;
            default -> false;
        };
    }

    private boolean isRidgeCandidate(Tile t, GeneratorSettings settings, int p85, double slope) {
        if (t.isRiver) return false;
        boolean high = t.elevation >= Math.max(settings.mountainMinElevation, p85);
        boolean sharp = slope >= 6.0;
        boolean mountainLike = switch (t.surfaceType) {
            case MOUNTAINS, HIGH_MOUNTAINS, MOUNTAINS_SNOW,
                    MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST, MOUNTAINS_TUNDRA, MOUNTAINS_ALPINE,
                    HIGHLANDS, PLATEAU -> true;
            default -> false;
        };
        return high && sharp && mountainLike;
    }

    private boolean isBasinCandidate(Tile t, int p10, double slope) {
        if (t.isRiver) return false;
        boolean low = t.elevation <= p10;
        boolean flat = slope <= 1.5;
        boolean depression = localDepression(t);
        if (!low || !flat) return false;
        if (!depression) return false;
        return switch (t.surfaceType) {
            case PLAINS, PLAINS_GRASS, PLAINS_FOREST,
                    GRASSLAND, SAVANNA, DRY_SAVANNA, FOREST, RAINFOREST, HILLS_RAINFOREST, MOUNTAINS_RAINFOREST,
                    DESERT_SAND, DESERT_ROCKY, DESERT, ROCKY_DESERT, SAND_DESERT, ROCK_DESERT, COLD_DESERT,
                    TUNDRA, PERMAFROST, SWAMP, MUD_SWAMP -> true;
            default -> false;
        };
    }

    private SurfaceType classifyRidge(SurfaceType base) {
        return switch (base) {
            case MOUNTAINS_SNOW, ICE, ICE_SHEET, GLACIER -> SurfaceType.RIDGE_SNOW;
            case TUNDRA, PERMAFROST, HILLS_TUNDRA -> SurfaceType.RIDGE_TUNDRA;
            case PLAINS_FOREST, FOREST, RAINFOREST, HILLS_FOREST, HILLS_RAINFOREST, MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST -> SurfaceType.RIDGE_FOREST;
            case PLAINS_GRASS, GRASSLAND, SAVANNA, HILLS_GRASS, HILLS_SAVANNA -> SurfaceType.RIDGE_GRASS;
            case DESERT_SAND, DESERT_ROCKY, DESERT, ROCKY_DESERT, SAND_DESERT, ROCK_DESERT, COLD_DESERT, HILLS_DESERT, MOUNTAINS_DESERT -> SurfaceType.RIDGE_DESERT;
            default -> SurfaceType.RIDGE_ROCK;
        };
    }

    private SurfaceType classifyCanyon(SurfaceType base) {
        return switch (base) {
            case MOUNTAINS, HIGH_MOUNTAINS, MOUNTAINS_ALPINE, MOUNTAINS_SNOW -> SurfaceType.CANYON_ROCK;
            case TUNDRA, PERMAFROST, HILLS_TUNDRA -> SurfaceType.CANYON_TUNDRA;
            case PLAINS_FOREST, FOREST, RAINFOREST, HILLS_FOREST, HILLS_RAINFOREST, MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST -> SurfaceType.CANYON_FOREST;
            case PLAINS_GRASS, GRASSLAND, SAVANNA, HILLS_GRASS, HILLS_SAVANNA -> SurfaceType.CANYON_GRASS;
            case DESERT_SAND, DESERT_ROCKY, DESERT, ROCKY_DESERT, SAND_DESERT, ROCK_DESERT, COLD_DESERT, HILLS_DESERT, MOUNTAINS_DESERT -> SurfaceType.CANYON_DESERT;
            default -> SurfaceType.CANYON;
        };
    }

    private SurfaceType classifyBasin(Tile t) {
        SurfaceType base = t.surfaceType;

        if (t.temperature <= 0) {
            return SurfaceType.BASIN_TUNDRA;
        }

        return switch (base) {
            case SWAMP, MUD_SWAMP -> SurfaceType.BASIN_SWAMP;
            case DESERT_SAND, DESERT_ROCKY, DESERT, ROCKY_DESERT, SAND_DESERT, ROCK_DESERT, COLD_DESERT -> SurfaceType.BASIN_DRY;
            case PLAINS_FOREST, FOREST, RAINFOREST, HILLS_FOREST, HILLS_RAINFOREST, MOUNTAINS_RAINFOREST -> SurfaceType.BASIN_FOREST;
            case PLAINS_GRASS, GRASSLAND, SAVANNA, DRY_SAVANNA, HILLS_GRASS, HILLS_SAVANNA, HILLS_DRY_SAVANNA, TUNDRA, PERMAFROST -> SurfaceType.BASIN_GRASS;
            default -> SurfaceType.BASIN_FLOOR;
        };
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

    private boolean localDepression(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return false;
        double sum = 0.0;
        for (Tile n : t.neighbors) {
            sum += n.elevation;
        }
        double avg = sum / t.neighbors.size();
        return (avg - t.elevation) >= 1.0;
    }

    private int percentile(int[] arr, double p) {
        if (arr.length == 0) return 0;
        int idx = (int) Math.round((arr.length - 1) * p);
        if (idx < 0) idx = 0;
        if (idx >= arr.length) idx = arr.length - 1;
        return arr[idx];
    }
}
