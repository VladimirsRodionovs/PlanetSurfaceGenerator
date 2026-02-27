package org.planet.core.generation;

import org.planet.core.model.SurfaceType;

import java.util.Comparator;
import java.util.Map;

public class WorldStatsReport {

    public static void print(WorldStats s) {
        System.out.println();
        System.out.println("========= WORLD STATS =========");
        System.out.println("Tiles: " + s.tileCount);
        System.out.println("Topology: pent(5-neigh)=" + s.pentCount + ", hex(6-neigh)=" + s.hexCount
                + ", other=" + (s.tileCount - s.pentCount - s.hexCount));

        System.out.println();
        System.out.println("Elevation: min=" + s.elevationMin + " max=" + s.elevationMax
                + " avg=" + fmt(s.elevationAvg));
        System.out.println("Temperature: min=" + s.tempMin + " max=" + s.tempMax
                + " avg=" + fmt(s.tempAvg));
        System.out.println("Pressure: min=" + s.pressureMin + " max=" + s.pressureMax
                + " avg=" + fmt(s.pressureAvg));

        System.out.println("Precip: min=" + fmt(s.precipMin) + " max=" + fmt(s.precipMax)
                + " avg=" + fmt(s.precipAvg));

        System.out.println("Evap:   min=" + fmt(s.evapMin) + " max=" + fmt(s.evapMax)
                + " avg=" + fmt(s.evapAvg));

        System.out.println("Atm q:  min=" + fmt(s.atmMoistMin) + " max=" + fmt(s.atmMoistMax)
                + " avg=" + fmt(s.atmMoistAvg) + " (g/kg)");

        System.out.println("Wind|v| (m/s): min=" + fmt(s.windMagMin) + " max=" + fmt(s.windMagMax)
                + " avg=" + fmt(s.windMagAvg));
        System.out.println("Wind cone (neighbors with dp>0.1): min=" + s.windOutMin + " max=" + s.windOutMax
                + " avg=" + fmt(s.windOutAvg));

        System.out.println("Rivers: " + s.riverCount);

        System.out.println();
        System.out.println("Surface types (top):");
        s.surfaceCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(12)
                .forEach(e -> System.out.println("  " + pad(e.getKey()) + " : " + e.getValue()));

        System.out.println("================================");
        System.out.println();

        System.out.println("Distance-to-water (steps):");
        for (int i = 0; i < s.waterDistCount.length; i++) {
            int count = s.waterDistCount[i];
            if (count <= 0) continue;
            double p = s.waterDistPrecip[i] / count;
            double e = s.waterDistEvap[i] / count;
            double a = s.waterDistAtm[i] / count;
            String label = (i < 6) ? String.valueOf(i) : "6+";
            System.out.println("  " + label + " : tiles=" + count
                    + " P=" + fmt(p) + " E=" + fmt(e) + " Atm=" + fmt(a));
        }
        System.out.println();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    private static String pad(SurfaceType t) {
        String name = (t == null) ? "null" : t.name();
        return String.format("%-18s", name);
    }
}
