package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class WorldStats {

    public int tileCount;

    // elevation
    public int elevationMin = Integer.MAX_VALUE;
    public int elevationMax = Integer.MIN_VALUE;
    public double elevationAvg;

    // temperature
    public int tempMin = Integer.MAX_VALUE;
    public int tempMax = Integer.MIN_VALUE;
    public double tempAvg;

    // pressure
    public int pressureMin = Integer.MAX_VALUE;
    public int pressureMax = Integer.MIN_VALUE;
    public double pressureAvg;

    // precip
    public double precipMin = Double.POSITIVE_INFINITY;
    public double precipMax = Double.NEGATIVE_INFINITY;
    public double precipAvg;

    // evap
    public double evapMin = Double.POSITIVE_INFINITY;
    public double evapMax = Double.NEGATIVE_INFINITY;
    public double evapAvg;

    // atmospheric moisture (g/kg)
    public double atmMoistMin = Double.POSITIVE_INFINITY;
    public double atmMoistMax = Double.NEGATIVE_INFINITY;
    public double atmMoistAvg;

    // wind magnitude
    public double windMagMin = Double.POSITIVE_INFINITY;
    public double windMagMax = Double.NEGATIVE_INFINITY;
    public double windMagAvg;

    // surface type histogram
    public final Map<SurfaceType, Integer> surfaceCounts = new EnumMap<>(SurfaceType.class);

    // topology sanity
    public int pentCount;
    public int hexCount;

    // rivers
    public int riverCount;

    // wind cone neighbor stats
    public int windOutMin = Integer.MAX_VALUE;
    public int windOutMax = Integer.MIN_VALUE;
    public double windOutAvg;

    // distance-to-water buckets (0..5, 6+)
    public final int[] waterDistCount = new int[7];
    public final double[] waterDistPrecip = new double[7];
    public final double[] waterDistEvap = new double[7];
    public final double[] waterDistAtm = new double[7];

    public static WorldStats compute(List<Tile> tiles) {
        WorldStats s = new WorldStats();
        s.tileCount = tiles.size();

        long elevSum = 0;
        long tempSum = 0;
        long pressSum = 0;
        double precipSum = 0;
        double evapSum = 0;
        double atmMoistSum = 0;
        double windSum = 0;
        long windOutSum = 0;

        int[] distToWater = computeDistanceToWater(tiles);

        for (Tile t : tiles) {
            // surface histogram
            s.surfaceCounts.merge(t.surfaceType, 1, Integer::sum);

            // topology counts
            if (t.neighbors != null) {
                if (t.neighbors.size() == 5) s.pentCount++;
                else if (t.neighbors.size() == 6) s.hexCount++;
            }

            // elevation
            s.elevationMin = Math.min(s.elevationMin, t.elevation);
            s.elevationMax = Math.max(s.elevationMax, t.elevation);
            elevSum += t.elevation;

            // temperature
            s.tempMin = Math.min(s.tempMin, t.temperature);
            s.tempMax = Math.max(s.tempMax, t.temperature);
            tempSum += t.temperature;

            // pressure
            s.pressureMin = Math.min(s.pressureMin, t.pressure);
            s.pressureMax = Math.max(s.pressureMax, t.pressure);
            pressSum += t.pressure;

            // precip
            s.precipMin = Math.min(s.precipMin, t.precipAvg);
            s.precipMax = Math.max(s.precipMax, t.precipAvg);
            precipSum += t.precipAvg;

            // evap
            s.evapMin = Math.min(s.evapMin, t.evapAvg);
            s.evapMax = Math.max(s.evapMax, t.evapAvg);
            evapSum += t.evapAvg;

            // atm moisture (g/kg)
            s.atmMoistMin = Math.min(s.atmMoistMin, t.atmMoist);
            s.atmMoistMax = Math.max(s.atmMoistMax, t.atmMoist);
            atmMoistSum += t.atmMoist;

            // wind magnitude
            double mag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
            s.windMagMin = Math.min(s.windMagMin, mag);
            s.windMagMax = Math.max(s.windMagMax, mag);
            windSum += mag;

            if (t.isRiver) s.riverCount++;

            int outCount = countWindOutNeighbors(t);
            s.windOutMin = Math.min(s.windOutMin, outCount);
            s.windOutMax = Math.max(s.windOutMax, outCount);
            windOutSum += outCount;

            int dist = 6;
            if (t.id >= 0 && t.id < distToWater.length) {
                int d = distToWater[t.id];
                if (d >= 0 && d <= 5) dist = d;
            }
            s.waterDistCount[dist]++;
            s.waterDistPrecip[dist] += t.precipAvg;
            s.waterDistEvap[dist] += t.evapAvg;
            s.waterDistAtm[dist] += t.atmMoist;
        }

        int n = Math.max(1, s.tileCount);
        s.elevationAvg = elevSum / (double) n;
        s.tempAvg = tempSum / (double) n;
        s.pressureAvg = pressSum / (double) n;
        s.precipAvg = precipSum / (double) n;
        s.evapAvg = evapSum / (double) n;
        s.atmMoistAvg = atmMoistSum / (double) n;
        s.windMagAvg = windSum / (double) n;
        s.windOutAvg = windOutSum / (double) n;

        // если нет тайлов (на всякий) — выровняем min/max
        if (s.tileCount == 0) {
            s.elevationMin = s.elevationMax = 0;
            s.tempMin = s.tempMax = 0;
            s.pressureMin = s.pressureMax = 0;
            s.precipMin = s.precipMax = 0;
            s.evapMin = s.evapMax = 0;
            s.atmMoistMin = s.atmMoistMax = 0;
            s.windMagMin = s.windMagMax = 0;
            s.windOutMin = s.windOutMax = 0;
        }

        return s;
    }

    private static int countWindOutNeighbors(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0;
        double mag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        if (mag < 1e-6) return 0;
        double wx = t.windX / mag;
        double wy = t.windY / mag;
        int count = 0;
        for (Tile nb : t.neighbors) {
            double dx = (nb.lon - t.lon) * 111.0 * Math.cos(Math.toRadians(t.lat));
            double dy = (nb.lat - t.lat) * 111.0;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6) continue;
            double dirx = dx / len;
            double diry = dy / len;
            double dp = wx * dirx + wy * diry;
            if (dp > 0.05) count++;
        }
        return count;
    }

    private static int[] computeDistanceToWater(List<Tile> tiles) {
        int n = tiles.size();
        int[] dist = new int[n];
        for (int i = 0; i < n; i++) dist[i] = -1;
        ArrayDeque<Tile> q = new ArrayDeque<>();
        for (Tile t : tiles) {
            if (isWaterSurface(t.surfaceType)) {
                dist[t.id] = 0;
                q.add(t);
            }
        }
        while (!q.isEmpty()) {
            Tile cur = q.poll();
            int d = dist[cur.id];
            if (cur.neighbors == null) continue;
            for (Tile nb : cur.neighbors) {
                if (nb == null) continue;
                int id = nb.id;
                if (id < 0 || id >= n) continue;
                if (dist[id] == -1) {
                    dist[id] = d + 1;
                    q.add(nb);
                }
            }
        }
        return dist;
    }

    private static boolean isWaterSurface(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP,
                    STEAM_SEA -> true;
            default -> false;
        };
    }
}
