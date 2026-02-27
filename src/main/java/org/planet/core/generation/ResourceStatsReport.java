package org.planet.core.generation;

import org.planet.core.model.Tile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ResourceStatsReport {
    private ResourceStatsReport() {
    }

    private static final class Acc {
        int entries = 0;
        final HashSet<Integer> tileIds = new HashSet<>();
        final ArrayList<Double> tonnes = new ArrayList<>();
        double sumTonnes = 0.0;
    }

    public static void printToStdout(List<Tile> tiles) {
        List<String> lines = buildDumpHeaderLines(tiles);
        System.out.println("[RESOURCE ABS] begin");
        for (String line : lines) {
            if (!line.startsWith("# resourceStat")) continue;
            System.out.println("[RESOURCE ABS] " + line.substring(2));
        }
        System.out.println("[RESOURCE ABS] end");
    }

    public static List<String> buildDumpHeaderLines(List<Tile> tiles) {
        Map<ResourceType, Acc> byType = new HashMap<>();
        for (Tile t : tiles) {
            if (t.resources == null) continue;
            for (ResourcePresence rp : t.resources) {
                if (rp == null || rp.type == null) continue;
                double tonnes = rp.tonnes;
                if (!(tonnes > 0.0) || Double.isNaN(tonnes) || Double.isInfinite(tonnes)) continue;
                Acc a = byType.computeIfAbsent(rp.type, k -> new Acc());
                a.entries++;
                a.tileIds.add(t.id);
                a.tonnes.add(tonnes);
                a.sumTonnes += tonnes;
            }
        }

        List<String> out = new ArrayList<>();
        out.add("# resourceStats.tonnes=absolute values by resource; ref range from generator log10 bounds");
        for (ResourceType type : ResourceType.values()) {
            Acc a = byType.get(type);
            if (a == null || a.entries == 0) continue;
            a.tonnes.sort(Double::compareTo);
            double min = a.tonnes.get(0);
            double max = a.tonnes.get(a.tonnes.size() - 1);
            double median = percentile(a.tonnes, 0.50);
            double p95 = percentile(a.tonnes, 0.95);

            double[] ref = ResourceGenerator.referenceLogRange(type);
            String refMin = "na";
            String refMax = "na";
            String below = "na";
            String above = "na";
            if (ref != null) {
                double refMinT = Math.pow(10.0, ref[0]);
                double refMaxT = Math.pow(10.0, ref[1]);
                int belowCnt = 0;
                int aboveCnt = 0;
                for (double v : a.tonnes) {
                    if (v < refMinT) belowCnt++;
                    if (v > refMaxT) aboveCnt++;
                }
                refMin = sci(refMinT);
                refMax = sci(refMaxT);
                below = pct(belowCnt, a.tonnes.size());
                above = pct(aboveCnt, a.tonnes.size());
            }

            out.add("# resourceStat\tcode=" + type.code
                    + "\tentries=" + a.entries
                    + "\ttiles=" + a.tileIds.size()
                    + "\tsum_t=" + sci(a.sumTonnes)
                    + "\tmin_t=" + sci(min)
                    + "\tp50_t=" + sci(median)
                    + "\tp95_t=" + sci(p95)
                    + "\tmax_t=" + sci(max)
                    + "\tref_min_t=" + refMin
                    + "\tref_max_t=" + refMax
                    + "\tbelow_ref=" + below
                    + "\tabove_ref=" + above);
        }
        return out;
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0.0;
        if (sorted.size() == 1) return sorted.get(0);
        double idx = p * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted.get(lo);
        double t = idx - lo;
        return sorted.get(lo) * (1.0 - t) + sorted.get(hi) * t;
    }

    private static String pct(int part, int total) {
        if (total <= 0) return "na";
        double v = 100.0 * part / (double) total;
        return String.format(Locale.US, "%.2f%%", v);
    }

    private static String sci(double v) {
        return String.format(Locale.US, "%.3e", v);
    }
}
