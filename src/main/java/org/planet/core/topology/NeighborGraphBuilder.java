package org.planet.core.topology;

import org.planet.core.model.Tile;

import java.util.*;

public class NeighborGraphBuilder {

    private final double maxNeighborDistanceDeg;

    public NeighborGraphBuilder(double maxNeighborDistanceDeg) {
        this.maxNeighborDistanceDeg = maxNeighborDistanceDeg;
    }

    public void build(List<Tile> tiles) {

        // Пространственный индекс по долготе
        Map<Integer, List<Tile>> lonBuckets = new HashMap<>();

        for (Tile t : tiles) {
            int bucket = lonBucket(t.lon);
            lonBuckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(t);

            if (t.neighbors == null) {
                t.neighbors = new ArrayList<>();
            } else {
                t.neighbors.clear();
            }
        }


        for (Tile tile : tiles) {

            int centerBucket = lonBucket(tile.lon);

            for (int dx = -1; dx <= 1; dx++) {
                int b = wrapBucket(centerBucket + dx);
                List<Tile> candidates = lonBuckets.get(b);
                if (candidates == null) continue;

                for (Tile other : candidates) {
                    if (other == tile) continue;
                    if (isNeighbor(tile, other)) {
                        tile.neighbors.add(other);
                    }
                }
            }
        }
    }

    private boolean isNeighbor(Tile a, Tile b) {
        double dLat = a.lat - b.lat;
        double dLon = wrappedLonDelta(a.lon, b.lon);
        double dist = Math.sqrt(dLat * dLat + dLon * dLon);
        return dist <= maxNeighborDistanceDeg;
    }

    private double wrappedLonDelta(double lon1, double lon2) {
        double d = lon1 - lon2;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private int lonBucket(double lon) {
        return (int) Math.floor((lon + 180.0) / maxNeighborDistanceDeg);
    }

    private int wrapBucket(int b) {
        int max = (int) Math.ceil(360.0 / maxNeighborDistanceDeg);
        if (b < 0) return b + max;
        if (b >= max) return b - max;
        return b;
    }
}

