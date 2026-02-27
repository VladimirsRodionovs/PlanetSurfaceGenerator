package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.PlanetConfig;

import java.util.*;

public class ImpactGenerator {

    public void apply(List<Tile> tiles, PlanetConfig planet, long seed) {
        Random rnd = new Random(seed + 4242);
        int n = tiles.size();

        double base = planet.hasAtmosphere ? 0.01 : 0.04;
        int craterCount = Math.max(1, (int) Math.round(n * base));

        for (int i = 0; i < craterCount; i++) {
            Tile center = tiles.get(rnd.nextInt(n));
            int radius = pickRadius(rnd); // 1..4 (in tile-steps)
            int depth = 1 + rnd.nextInt(2 + radius);

            applyCrater(center, radius, depth, !planet.hasAtmosphere, seed);
        }
    }

    private void applyCrater(Tile center, int radiusTiles, int depth, boolean airless, long seed) {
        double rKm = radiusTiles * 100.0;
        double rimKm = rKm * 1.35;
        List<Set<Tile>> layers = bfsLayers(center, radiusTiles + 3);
        long craterSeed = seed + center.id * 92821L + radiusTiles * 31L;

        for (int r = 0; r < layers.size(); r++) {
            Set<Tile> layer = layers.get(r);
            for (Tile t : layer) {
                double distKm = angularDistanceKm(center, t);
                double jitter = (noise01(craterSeed, t.id) - 0.5) * 0.25; // +/-12.5%
                double effR = rKm * (1.0 + jitter);
                if (distKm <= effR) {
                    double norm = Math.min(1.0, distKm / Math.max(1.0, effR));
                    int d = Math.max(1, (int) Math.round(depth * (1.0 - norm)));
                    t.elevation = Math.max(0, t.elevation - d);
                    if (t.surfaceType != SurfaceType.OCEAN && t.surfaceType != SurfaceType.LAVA_OCEAN) {
                        t.surfaceType = SurfaceType.CRATERED_SURFACE;
                    }
                } else if (distKm <= rimKm) {
                    if (airless && t.surfaceType != SurfaceType.OCEAN && t.surfaceType != SurfaceType.LAVA_OCEAN) {
                        t.surfaceType = SurfaceType.REGOLITH;
                    }
                }
            }
        }
    }

    private int pickRadius(Random rnd) {
        double x = rnd.nextDouble();
        if (x < 0.6) return 1;
        if (x < 0.85) return 2;
        if (x < 0.95) return 3;
        return 4;
    }

    private List<Set<Tile>> bfsLayers(Tile center, int maxR) {
        List<Set<Tile>> layers = new ArrayList<>();
        Set<Tile> visited = new HashSet<>();
        Queue<Tile> q = new ArrayDeque<>();
        q.add(center);
        visited.add(center);
        int r = 0;

        while (!q.isEmpty() && r <= maxR) {
            int size = q.size();
            Set<Tile> layer = new HashSet<>();
            for (int i = 0; i < size; i++) {
                Tile t = q.poll();
                if (t != null) layer.add(t);
                if (t != null && t.neighbors != null) {
                    for (Tile nb : t.neighbors) {
                        if (visited.add(nb)) q.add(nb);
                    }
                }
            }
            layers.add(layer);
            r++;
        }
        return layers;
    }

    private double angularDistanceKm(Tile a, Tile b) {
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLon = Math.toRadians(b.lon - a.lon);
        double s =
                Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                        Math.cos(Math.toRadians(a.lat)) *
                                Math.cos(Math.toRadians(b.lat)) *
                                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
        return 6371.0 * c;
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
}
