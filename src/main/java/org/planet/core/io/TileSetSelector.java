package org.planet.core.io;

public class TileSetSelector {

    private static final double[] RADII_KM = {340, 650, 1300, 2600, 5200};
    private static final String[] FILES = {
            "LatLongTileID2_v2.txt",
            "LatLongTileID3_v2.txt",
            "LatLongTileID4_v2.txt",
            "LatLongTileID5_v2.txt",
            "LatLongTileID6_v2.txt"
    };

    public static String pickTilesPath(double radiusKm) {
        if (Double.isNaN(radiusKm) || radiusKm <= 0) {
            return FILES[3]; // D5 fallback
        }

        int best = 0;
        double bestDist = Math.abs(radiusKm - RADII_KM[0]);
        for (int i = 1; i < RADII_KM.length; i++) {
            double d = Math.abs(radiusKm - RADII_KM[i]);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }

        // D6 пока нет — используем D5
        if (best == 4) {
            return FILES[3];
        }
        return FILES[best];
    }
}
