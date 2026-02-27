package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.RiverBaseType;
import org.planet.core.model.config.PlanetConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RiverGenerator {

    private static final double DAY_SECONDS = 86_400.0;
    private static final double FALLBACK_TILE_AREA_M2 = 9.35e9; // ~hex, 120 km across flats
    private static final double SOIL_INDEX_PER_KGM2 = 1.8;      // align with WindGenerator soilPerMm
    private static final double ROUTE_FLUX_SCALE = 1.4e-4;      // synthetic kg/s from routed catchment area
    private static final double RIVER_START_KG_S = 480_000.0;   // 480 t/s
    private static final double SOURCE_TOP_POTENTIAL_FLOW_FRACTION = 0.20; // top 20% by potential flow
    private static final int SOURCE_MIN_ELEVATION = 2;
    private static final int MAX_CARVE_UPHILL = 2;
    private static final double PLATEAU_DEVIATION_PROB = 0.40;
    private static final int FAR_DIST = 1_000_000;
    private long generationSeed = 0L;

    public void generate(List<Tile> tiles, PlanetConfig planet) {
        generate(tiles, planet, 0L);
    }

    public void generate(List<Tile> tiles, PlanetConfig planet, long baseSeed) {
        this.generationSeed = baseSeed;
        resetRiverState(tiles);
        if (!planet.hasAtmosphere || planet.waterCoverageOrdinal == 0 || !hasAnyLiquidWater(tiles)) {
            return;
        }

        int n = tiles.size();
        double tileAreaM2 = tileAreaM2(tiles, planet);
        double[] wetness = new double[n];
        double[] baseFlowKgS = new double[n];
        double[] runoffFlowKgS = new double[n];
        boolean[] thermalBlock = new boolean[n];
        boolean[] candidate = new boolean[n];
        for (int i = 0; i < n; i++) {
            Tile t = tiles.get(i);
            double w = wetnessIndex(t);
            wetness[i] = w;
            thermalBlock[i] = !isWater(t.surfaceType) && !isFrozenLand(t.surfaceType) && !canSustainLiquidRiver(t, planet);
            if (isSeaOrOcean(t.surfaceType) || isFrozenLand(t.surfaceType)) {
                baseFlowKgS[i] = 0.0;
                runoffFlowKgS[i] = 0.0;
                t.riverPotentialKgS = 0.0;
                candidate[i] = false;
            } else if (thermalBlock[i]) {
                baseFlowKgS[i] = 0.0;
                runoffFlowKgS[i] = 0.0;
                t.riverPotentialKgS = 0.0;
                candidate[i] = false;
            } else {
                double baseArea = tileAreaM2 * w;
                double runoff = validPhysFlux(t.surfaceRunoffKgM2Day) ? t.surfaceRunoffKgM2Day : 0.0;
                double runoffKgS = runoff * tileAreaM2 / DAY_SECONDS;
                runoffFlowKgS[i] = runoffKgS;
                double q = baseArea * ROUTE_FLUX_SCALE + runoffKgS;
                baseFlowKgS[i] = q;
                t.riverPotentialKgS = q;
                candidate[i] = false;
            }
        }

        double flowCutoff = computePotentialFlowTopCutoff(tiles, SOURCE_TOP_POTENTIAL_FLOW_FRACTION, SOURCE_MIN_ELEVATION);
        for (int i = 0; i < n; i++) {
            Tile t = tiles.get(i);
            if (isWater(t.surfaceType) || isFrozenLand(t.surfaceType) || thermalBlock[i]) continue;
            if (t.elevation >= SOURCE_MIN_ELEVATION && baseFlowKgS[i] >= flowCutoff) {
                candidate[i] = true;
            }
        }

        int[] waterDist = buildWaterDistance(tiles);
        int[] downstream = new int[n];
        int[] canyonInc = new int[n];
        boolean[] channel = new boolean[n];
        boolean[] source = new boolean[n];
        boolean[] noJoin = new boolean[n];
        Arrays.fill(downstream, -1);

        Integer[] candidateOrder = new Integer[n];
        for (int i = 0; i < n; i++) candidateOrder[i] = i;
        Arrays.sort(candidateOrder, Comparator
                .comparingInt((Integer i) -> tiles.get(i).elevation)
                .thenComparingDouble(i -> baseFlowKgS[i])
                .thenComparingInt(i -> i)
                .reversed());

        for (int idx : candidateOrder) {
            if (!candidate[idx]) continue;
            source[idx] = true;
            noJoin[idx] = true;
        }

        List<Integer> sourceOrder = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (source[i]) sourceOrder.add(i);
        }
        sourceOrder.sort(Comparator
                .comparingInt((Integer i) -> tiles.get(i).elevation)
                .thenComparingDouble(i -> baseFlowKgS[i])
                .thenComparingInt(i -> i)
                .reversed());

        int routeSuccess = 0;
        int routeFail = 0;
        for (int src : sourceOrder) {
            boolean ok = traceFromSource(src, tiles, waterDist, noJoin, thermalBlock, channel, downstream, canyonInc);
            if (ok) {
                routeSuccess++;
            } else {
                routeFail++;
            }
        }
        System.out.println("[RIVER ROUTE] attempts=" + sourceOrder.size()
                + " success=" + routeSuccess
                + " fail=" + routeFail);

        List<List<Integer>> upstream = new ArrayList<>();
        for (int i = 0; i < n; i++) upstream.add(new ArrayList<>());
        int[] indegree = new int[n];
        for (int i = 0; i < n; i++) {
            int dn = downstream[i];
            if (dn >= 0 && dn < n && channel[i] && channel[dn]) {
                upstream.get(dn).add(i);
                indegree[dn]++;
            }
        }

        double[] inflow = new double[n];
        double[] discharge = new double[n];
        double sumQIn = 0.0;
        double sumQOut = 0.0;
        double sumGain = 0.0;
        double sumLoss = 0.0;
        double sumRetention = 0.0;
        int activeSegments = 0;

        ArrayDeque<Integer> qNodes = new ArrayDeque<>();
        boolean[] processed = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (channel[i] && indegree[i] == 0) {
                qNodes.add(i);
            }
        }

        while (!qNodes.isEmpty()) {
            int idx = qNodes.poll();
            if (!channel[idx] || processed[idx]) continue;
            processed[idx] = true;

            double qIn = (source[idx] ? baseFlowKgS[idx] : 0.0) + inflow[idx] + runoffFlowKgS[idx];
            double exchange = localMoistureExchangeKgS(tiles.get(idx), qIn, baseFlowKgS[idx]);
            applyExchangeToSoilMoisture(tiles.get(idx), exchange, tileAreaM2);
            double q = Math.max(0.0, qIn + exchange);
            discharge[idx] = q;
            activeSegments++;
            sumQIn += qIn;
            sumQOut += q;
            if (exchange >= 0.0) sumGain += exchange;
            else sumLoss += -exchange;

            int dn = downstream[idx];
            if (dn >= 0 && dn < n && channel[dn]) {
                double retain = channelRetentionFraction(tiles.get(idx), q);
                double pass = q;
                sumRetention += (q - pass);
                inflow[dn] += Math.max(0.0, pass);
                indegree[dn] = Math.max(0, indegree[dn] - 1);
                if (indegree[dn] == 0) {
                    qNodes.add(dn);
                }
            }
        }

        // Fallback for rare route cycles: process remaining nodes once.
        int cycleNodes = 0;
        for (int idx = 0; idx < n; idx++) {
            if (!channel[idx] || processed[idx]) continue;
            cycleNodes++;
            processed[idx] = true;

            double qIn = (source[idx] ? baseFlowKgS[idx] : 0.0) + inflow[idx] + runoffFlowKgS[idx];
            double exchange = localMoistureExchangeKgS(tiles.get(idx), qIn, baseFlowKgS[idx]);
            applyExchangeToSoilMoisture(tiles.get(idx), exchange, tileAreaM2);
            double q = Math.max(0.0, qIn + exchange);
            discharge[idx] = q;
            activeSegments++;
            sumQIn += qIn;
            sumQOut += q;
            if (exchange >= 0.0) sumGain += exchange;
            else sumLoss += -exchange;

            int dn = downstream[idx];
            if (dn >= 0 && dn < n && channel[dn]) {
                double retain = channelRetentionFraction(tiles.get(idx), q);
                double pass = q;
                sumRetention += (q - pass);
                inflow[dn] += Math.max(0.0, pass);
            }
        }

        System.out.println("[RIVER BAL] seg=" + activeSegments
                + " qIn=" + fmt(sumQIn)
                + " qOut=" + fmt(sumQOut)
                + " gain=" + fmt(sumGain)
                + " loss=" + fmt(sumLoss)
                + " retain=" + fmt(sumRetention)
                + " cycles=" + cycleNodes);

        double maxLandQ = 0.0;
        for (int i = 0; i < n; i++) {
            if (channel[i] && !isWater(tiles.get(i).surfaceType) && !isFrozenLand(tiles.get(i).surfaceType)) {
                maxLandQ = Math.max(maxLandQ, discharge[i]);
            }
        }
        if (maxLandQ <= 1e-9) maxLandQ = 1.0;

        for (int i = 0; i < n; i++) {
            Tile t = tiles.get(i);
            t.canyonDepth += canyonInc[i];
            t.riverFrom.clear();
            if (channel[i]) {
                t.riverTo = downstream[i];
                for (int u : upstream.get(i)) {
                    if (u >= 0 && u < n && channel[u]) {
                        t.riverFrom.add(u);
                    }
                }
                t.isRiver = true;
                t.riverDischargeKgS = discharge[i];
                t.riverDischargeTps = discharge[i] / 1000.0;
                t.riverFlow = clamp(discharge[i] / maxLandQ, 0.0, 1.0);
                double q = Math.max(discharge[i], source[i] ? baseFlowKgS[i] : 0.0);
                t.riverOrder = riverOrder(q);
                RiverBaseType base = classifyRiverBaseType(t, tiles, downstream[i], discharge[i], t.riverFrom);
                t.riverBaseType = base;
                t.riverTag = buildRiverTag(base, t.riverTo, t.riverFrom);
                t.riverType = legacyRiverType(base, t.riverOrder);
            } else {
                t.isRiver = false;
                t.riverTo = -1;
                t.riverDischargeKgS = 0.0;
                t.riverDischargeTps = 0.0;
                t.riverFlow = 0.0;
                t.riverOrder = 0;
                t.riverBaseType = RiverBaseType.NONE;
                t.riverTag = "";
                t.riverType = 0;
            }
        }

        // Wet closed basins without clear outlet become swamps.
        for (int i = 0; i < n; i++) {
            Tile t = tiles.get(i);
            if (isWater(t.surfaceType) || isFrozenLand(t.surfaceType) || t.isRiver) continue;
            boolean hasOutlet = downstream[i] >= 0 && waterDist[i] < FAR_DIST;
            boolean nearRiver = hasRiverNeighbor(t);
            if (wetness[i] > 0.78 && (!hasOutlet || !nearRiver) && !thermalBlock[i]) {
                t.surfaceType = (t.temperature > 100.0) ? SurfaceType.MUD_SWAMP : SurfaceType.SWAMP;
            }
        }
    }

    private void resetRiverState(List<Tile> tiles) {
        for (Tile t : tiles) {
            t.riverFlow = 0.0;
            t.riverOrder = 0;
            t.isRiver = false;
            t.canyonDepth = 0;
            t.riverType = 0;
            t.riverTo = -1;
            t.riverFrom.clear();
            t.riverBaseType = RiverBaseType.NONE;
            t.riverTag = "";
            t.riverDischargeKgS = 0.0;
            t.riverDischargeTps = 0.0;
            t.riverPotentialKgS = 0.0;
        }
    }

    private boolean traceFromSource(int sourceId,
                                    List<Tile> tiles,
                                    int[] waterDist,
                                    boolean[] noJoin,
                                    boolean[] thermalBlock,
                                    boolean[] channel,
                                    int[] downstream,
                                    int[] canyonInc) {
        int n = tiles.size();
        boolean[] onPath = new boolean[n];
        @SuppressWarnings("unchecked")
        Set<Integer>[] triedNext = new Set[n];
        List<Integer> path = new ArrayList<>();
        path.add(sourceId);
        onPath[sourceId] = true;

        int guard = 0;
        boolean reached = false;
        while (!path.isEmpty() && guard < n * 4) {
            guard++;
            int cur = path.get(path.size() - 1);
            Tile t = tiles.get(cur);
            if (isWater(t.surfaceType)) {
                reached = true;
                break;
            }
            if (isFrozenLand(t.surfaceType)) {
                return false;
            }
            if (channel[cur] && cur != sourceId) {
                reached = true;
                break;
            }

            if (triedNext[cur] == null) triedNext[cur] = new HashSet<>();
            int next = pickRouteStepWithRetries(sourceId, guard, t, waterDist, noJoin, thermalBlock, onPath, triedNext[cur]);
            if (next < 0) {
                onPath[cur] = false;
                path.remove(path.size() - 1);
                continue;
            }
            triedNext[cur].add(next);

            if (!isWater(tiles.get(next).surfaceType) && channel[next] && next != sourceId) {
                path.add(next);
                reached = true;
                break;
            }
            if (onPath[next]) {
                continue;
            }
            path.add(next);
            onPath[next] = true;
        }

        if (!reached || path.size() < 2) {
            return false;
        }

        for (int i = 0; i + 1 < path.size(); i++) {
            int from = path.get(i);
            int to = path.get(i + 1);
            Tile tf = tiles.get(from);
            Tile tt = tiles.get(to);
            downstream[from] = to;
            channel[from] = true;
            if (tt.elevation > tf.elevation) {
                canyonInc[from] += 1;
            }
            if (isWater(tt.surfaceType)) {
                break;
            }
            if (channel[to] && to != sourceId) {
                break;
            }
        }
        return true;
    }

    private int pickRouteStepWithRetries(int sourceId,
                                         int step,
                                         Tile current,
                                         int[] waterDist,
                                         boolean[] noJoin,
                                         boolean[] thermalBlock,
                                         boolean[] onPath,
                                         Set<Integer> tried) {
        if (current.neighbors == null || current.neighbors.isEmpty()) return -1;
        int currentDist = (current.id >= 0 && current.id < waterDist.length) ? waterDist[current.id] : FAR_DIST;

        List<Tile> lower = new ArrayList<>();
        List<Tile> equal = new ArrayList<>();
        List<Tile> higher = new ArrayList<>();
        for (Tile nb : current.neighbors) {
            int j = nb.id;
            if (j < 0 || j >= waterDist.length) continue;
            if (tried.contains(j)) continue;
            if (!isWater(nb.surfaceType) && noJoin[j] && j != sourceId) continue;
            if (!isWater(nb.surfaceType) && thermalBlock[j]) continue;
            if (!isWater(nb.surfaceType) && isFrozenLand(nb.surfaceType)) continue;
            if (onPath[j]) continue;
            if (current.elevation > nb.elevation) {
                lower.add(nb);
            } else if (current.elevation == nb.elevation) {
                equal.add(nb);
            } else if ((nb.elevation - current.elevation) <= MAX_CARVE_UPHILL) {
                higher.add(nb);
            }
        }

        lower.sort(routeComparator(waterDist));
        equal.sort(routeComparator(waterDist));
        higher.sort(routeComparator(waterDist));

        Tile pickLowerNoWorse = pickCandidateNearBest(lower, waterDist, currentDist, sourceId, step, current.id, true);
        if (pickLowerNoWorse != null) return pickLowerNoWorse.id;

        Tile pickEqualNoWorse = pickCandidateNearBest(equal, waterDist, currentDist, sourceId, step, current.id, true);
        if (pickEqualNoWorse != null) return pickEqualNoWorse.id;

        Tile pickHigherNoWorse = pickCandidateNearBest(higher, waterDist, currentDist, sourceId, step, current.id, true);
        if (pickHigherNoWorse != null) return pickHigherNoWorse.id;

        // Fallback: allow temporary move away from target only when no non-worsening step exists.
        Tile pickLowerAny = pickCandidateNearBest(lower, waterDist, currentDist, sourceId, step, current.id, false);
        if (pickLowerAny != null) return pickLowerAny.id;

        Tile pickEqualAny = pickCandidateNearBest(equal, waterDist, currentDist, sourceId, step, current.id, false);
        if (pickEqualAny != null) return pickEqualAny.id;

        Tile pickHigherAny = pickCandidateNearBest(higher, waterDist, currentDist, sourceId, step, current.id, false);
        if (pickHigherAny != null) return pickHigherAny.id;
        return -1;
    }

    private Tile pickCandidateNearBest(List<Tile> sortedCandidates,
                                       int[] waterDist,
                                       int currentDist,
                                       int sourceId,
                                       int step,
                                       int currentId,
                                       boolean requireNoWorse) {
        if (sortedCandidates == null || sortedCandidates.isEmpty()) return null;

        int nearest = FAR_DIST;
        List<Tile> inBand = new ArrayList<>();
        for (Tile t : sortedCandidates) {
            int d = (t.id >= 0 && t.id < waterDist.length) ? waterDist[t.id] : FAR_DIST;
            if (requireNoWorse && d > currentDist) continue;
            if (nearest == FAR_DIST) nearest = d;

            int span = (int) Math.round(nearest * 1.5); // +150% to nearest
            int maxDist = nearest + Math.max(0, span);
            if (d > maxDist) {
                // candidates are sorted by water distance; no need to scan farther
                break;
            }
            inBand.add(t);
            if (inBand.size() >= 5) break;
        }
        if (inBand.isEmpty()) return null;
        if (inBand.size() == 1) return inBand.get(0);

        int pick = deterministicPick(sourceId, step * 31 + 11, currentId * 17 + 5, inBand.size());
        // Keep plateau "straightness" tendency: for equal-elevation pools, occasionally choose nearest.
        if (deviationRoll01(sourceId, step, currentId) >= PLATEAU_DEVIATION_PROB) {
            pick = 0;
        }
        return inBand.get(pick);
    }

    private Comparator<Tile> routeComparator(int[] waterDist) {
        return Comparator
                .comparingInt((Tile t) -> waterDist[t.id])
                .thenComparingInt(t -> t.elevation)
                .thenComparingInt(t -> t.id);
    }

    private double localMoistureExchangeKgS(Tile t, double qInKgS, double baseFlowKgS) {
        if (isWater(t.surfaceType) || isFrozenLand(t.surfaceType)) return 0.0;

        double soil = clamp(Double.isNaN(t.moisture) ? 35.0 : t.moisture, 0.0, 100.0);
        double surplus = clamp((soil - 55.0) / 45.0, 0.0, 1.0);
        double deficit = clamp((40.0 - soil) / 40.0, 0.0, 1.0);

        double gain = (0.58 * baseFlowKgS + 0.12 * qInKgS) * surplus;
        double loss = (0.12 * qInKgS + 0.05 * baseFlowKgS) * deficit;

        if (t.surfaceType == SurfaceType.SWAMP || t.surfaceType == SurfaceType.MUD_SWAMP || t.surfaceType == SurfaceType.BASIN_SWAMP) {
            gain *= 1.18;
            loss *= 0.75;
        }
        if (t.surfaceType == SurfaceType.DESERT_SAND || t.surfaceType == SurfaceType.DESERT_ROCKY || t.surfaceType == SurfaceType.ROCKY_DESERT) {
            gain *= 0.60;
            loss *= 1.25;
        }

        double delta = gain - loss;
        double minDelta = -qInKgS * 0.45;
        double maxDelta = Math.max(baseFlowKgS * 1.15, qInKgS * 0.55);
        return clamp(delta, minDelta, maxDelta);
    }

    private void applyExchangeToSoilMoisture(Tile t, double exchangeKgS, double tileAreaM2) {
        if (isWater(t.surfaceType) || isFrozenLand(t.surfaceType) || tileAreaM2 <= 1e-9) return;
        // exchange < 0: river loses water to tile => soil moisture should increase.
        // exchange > 0: tile feeds river => soil moisture should decrease.
        double exchangeKgM2Day = exchangeKgS * DAY_SECONDS / tileAreaM2;
        double soilDelta = -exchangeKgM2Day * SOIL_INDEX_PER_KGM2;
        soilDelta = clamp(soilDelta, -18.0, 18.0);
        double soil = Double.isNaN(t.moisture) ? 40.0 : t.moisture;
        t.moisture = clamp(soil + soilDelta, 0.0, 100.0);
    }

    private String fmt(double v) {
        return String.format("%.0f", v);
    }

    private double computePotentialFlowTopCutoff(List<Tile> tiles, double topFraction, int minElevation) {
        int cnt = 0;
        for (Tile t : tiles) {
            if (!isWater(t.surfaceType) && !isFrozenLand(t.surfaceType) && t.elevation >= minElevation) cnt++;
        }
        if (cnt == 0) return Double.POSITIVE_INFINITY;

        double[] values = new double[cnt];
        int k = 0;
        for (Tile t : tiles) {
            if (isWater(t.surfaceType) || isFrozenLand(t.surfaceType) || t.elevation < minElevation) continue;
            values[k++] = Math.max(0.0, t.riverPotentialKgS);
        }
        Arrays.sort(values);
        int startTop = (int) Math.floor((1.0 - clamp(topFraction, 0.0, 1.0)) * (cnt - 1));
        startTop = Math.max(0, Math.min(cnt - 1, startTop));
        return values[startTop];
    }

    private int[] buildWaterDistance(List<Tile> tiles) {
        int n = tiles.size();
        int[] dist = new int[n];
        Arrays.fill(dist, FAR_DIST);

        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (isWater(tiles.get(i).surfaceType)) {
                dist[i] = 0;
                q.add(i);
            }
        }

        while (!q.isEmpty()) {
            int v = q.poll();
            Tile tv = tiles.get(v);
            if (tv.neighbors == null || tv.neighbors.isEmpty()) continue;
            int nd = dist[v] + 1;
            for (Tile nb : tv.neighbors) {
                int j = nb.id;
                if (j < 0 || j >= n) continue;
                if (nd < dist[j]) {
                    dist[j] = nd;
                    q.add(j);
                }
            }
        }
        return dist;
    }

    private int countCandidateOrNoJoinNeighbors(Tile t, boolean[] candidate, boolean[] noJoin) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0;
        int c = 0;
        for (Tile nb : t.neighbors) {
            int j = nb.id;
            if (j < 0 || j >= candidate.length) continue;
            if (candidate[j] || noJoin[j]) c++;
        }
        return c;
    }

    private double deviationRoll01(int sourceId, int step, int tileId) {
        long x = 0x9E3779B97F4A7C15L ^ generationSeed;
        x ^= ((long) sourceId + 0xBF58476D1CE4E5B9L);
        x ^= ((long) step << 17);
        x ^= ((long) tileId << 33);
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        long mantissa = (x >>> 11) & ((1L << 53) - 1);
        return mantissa / (double) (1L << 53);
    }

    private int deterministicPick(int sourceId, int step, int tileId, int bound) {
        if (bound <= 1) return 0;
        double r = deviationRoll01(sourceId * 31 + 7, step * 17 + 3, tileId * 13 + 5);
        int idx = (int) Math.floor(r * bound);
        if (idx < 0) idx = 0;
        if (idx >= bound) idx = bound - 1;
        return idx;
    }

    private RiverBaseType classifyRiverBaseType(Tile t,
                                                List<Tile> tiles,
                                                int downstreamId,
                                                double qKgS,
                                                List<Integer> upstream) {
        boolean big = t.riverOrder >= 3;
        if (downstreamId >= 0 && downstreamId < tiles.size()) {
            SurfaceType dnType = tiles.get(downstreamId).surfaceType;
            if (isSeaOrOcean(dnType) && qKgS >= 2_500_000.0) {
                return RiverBaseType.DELTA;
            }
        }
        if (downstreamId >= 0 && downstreamId < tiles.size()) {
            int drop = t.elevation - tiles.get(downstreamId).elevation;
            if (drop >= 5 && qKgS >= RIVER_START_KG_S * 1.5) {
                return RiverBaseType.WATERFALL;
            }
        }
        if (t.canyonDepth >= 2 && big) return RiverBaseType.CANYON;
        if (isValleyTerrain(t) && big) return RiverBaseType.VALLEY;
        if (upstream == null || upstream.isEmpty()) return RiverBaseType.SOURCE;
        return sizeBaseType(qKgS);
    }

    private RiverBaseType sizeBaseType(double qKgS) {
        if (qKgS < 250_000.0) return RiverBaseType.SMALL_RIVER;
        if (qKgS < 900_000.0) return RiverBaseType.MEDIUM_RIVER;
        if (qKgS < 2_500_000.0) return RiverBaseType.LARGE_RIVER;
        return RiverBaseType.VERY_LARGE_RIVER;
    }

    private int legacyRiverType(RiverBaseType base, int order) {
        return switch (base) {
            case DELTA -> 6;
            case WATERFALL -> 5;
            case CANYON -> 4;
            case VALLEY -> 3;
            case VERY_LARGE_RIVER, LARGE_RIVER, MEDIUM_RIVER -> 2;
            case SOURCE, SMALL_RIVER -> 1;
            default -> (order > 0 ? 1 : 0);
        };
    }

    private String buildRiverTag(RiverBaseType base, int to, List<Integer> from) {
        StringBuilder sb = new StringBuilder(base.name());
        sb.append("_").append(to);
        if (from != null && !from.isEmpty()) {
            List<Integer> sorted = new ArrayList<>(from);
            sorted.sort(Integer::compareTo);
            for (int v : sorted) {
                sb.append("_").append(v);
            }
        }
        return sb.toString();
    }

    private int riverOrder(double qKgS) {
        if (qKgS < RIVER_START_KG_S) return 0;
        if (qKgS < 250_000.0) return 1;
        if (qKgS < 600_000.0) return 2;
        if (qKgS < 1_500_000.0) return 3;
        if (qKgS < 3_500_000.0) return 4;
        return 5;
    }

    private double channelRetentionFraction(Tile t, double qKgS) {
        double slope = maxSlope(t);
        double rough = switch (t.surfaceType) {
            case SWAMP, MUD_SWAMP, BASIN_SWAMP -> 0.14;
            case DESERT_SAND, DESERT_ROCKY, ROCKY_DESERT -> 0.08;
            default -> 0.10;
        };
        double flowTerm = clamp(1.0 - qKgS / 2_000_000.0, 0.35, 1.0);
        double slopeTerm = clamp(1.0 - slope / 12.0, 0.45, 1.05);
        return clamp(rough * flowTerm * slopeTerm, 0.02, 0.16);
    }

    private boolean isValleyTerrain(Tile t) {
        return switch (t.surfaceType) {
            case HILLS, HILLS_GRASS, HILLS_FOREST, HILLS_RAINFOREST, HIGHLANDS, PLATEAU, BASIN_FLOOR -> true;
            default -> false;
        };
    }

    private double wetnessIndex(Tile t) {
        double moist = clamp((Double.isNaN(t.moisture) ? 35.0 : t.moisture) / 100.0, 0.0, 1.0);
        double precip = validPhysFlux(t.precipKgM2Day) ? t.precipKgM2Day : nan0(t.precipAvg);
        double precipNorm = clamp(precip / 28.0, 0.0, 1.0);
        double atm = clamp(nan0(t.atmMoist) / 28.0, 0.0, 1.0);
        double slopePenalty = clamp(maxSlope(t) / 25.0, 0.0, 0.45);
        double wet = 0.16 + moist * 0.56 + precipNorm * 0.20 + atm * 0.10 - slopePenalty * 0.12;
        if (isLakeLike(t.surfaceType)) wet += 0.10;
        return clamp(wet, 0.05, 1.15);
    }

    private double tileAreaM2(List<Tile> tiles, PlanetConfig planet) {
        if (planet != null && planet.radiusKm > 10.0 && !tiles.isEmpty()) {
            double r = planet.radiusKm * 1000.0;
            return (4.0 * Math.PI * r * r) / tiles.size();
        }
        return FALLBACK_TILE_AREA_M2;
    }

    private boolean isLakeLike(SurfaceType st) {
        return switch (st) {
            case LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID -> true;
            default -> false;
        };
    }

    private boolean hasRiverNeighbor(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return false;
        for (Tile nb : t.neighbors) {
            if (nb.isRiver) return true;
        }
        return false;
    }

    private boolean isWater(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    SHALLOW_SEA,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP, STEAM_SEA -> true;
            default -> false;
        };
    }

    private boolean isSeaOrOcean(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    SHALLOW_SEA,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP, STEAM_SEA -> true;
            default -> false;
        };
    }

    private boolean isFrozenLand(SurfaceType st) {
        return switch (st) {
            case ICE, ICE_SHEET, GLACIER -> true;
            default -> false;
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

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double nan0(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    private boolean validPhysFlux(double v) {
        return !Double.isNaN(v) && v >= 0.0 && v <= 20_000.0;
    }

    private boolean hasAnyLiquidWater(List<Tile> tiles) {
        for (Tile t : tiles) {
            if (isWater(t.surfaceType)) return true;
        }
        return false;
    }

    private boolean canSustainLiquidRiver(Tile t, PlanetConfig planet) {
        double pBarPlanet = (planet == null) ? 0.0 : planet.atmosphereDensity;
        double pBarTile = (t == null) ? 0.0 : Math.max(0.0, t.pressure) / 1000.0;
        double pBar = Math.max(0.05, Math.max(pBarPlanet, pBarTile));
        double boil = boilingPointC(pBar);
        double temp = firstFinite(
                t == null ? Double.NaN : t.tempMaxInterseason,
                t == null ? Double.NaN : t.tempMax,
                t == null ? Double.NaN : t.tempWarm,
                t == null ? Double.NaN : t.biomeTempWarm,
                t == null ? Double.NaN : t.temperature
        );
        return temp <= (boil + 1.5);
    }

    private double firstFinite(double... vals) {
        if (vals == null || vals.length == 0) return Double.NaN;
        for (double v : vals) {
            if (!Double.isNaN(v) && Double.isFinite(v)) return v;
        }
        return Double.NaN;
    }

    private double boilingPointC(double pBar) {
        double p = Math.max(0.01, pBar);
        double pMmHg = p * 750.062;
        double A = 8.14019;
        double B = 1810.94;
        double C = 244.485;
        return B / (A - Math.log10(pMmHg)) - C;
    }
}
