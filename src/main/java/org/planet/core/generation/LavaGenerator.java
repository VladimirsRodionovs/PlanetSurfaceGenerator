package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;

import java.util.List;
import java.util.Random;

public class LavaGenerator {

    public void apply(List<Tile> tiles, long seed) {
        Random rnd = new Random(seed + 2025);

        // базово вся поверхность в лавовом океане
        for (Tile t : tiles) {
            t.surfaceType = SurfaceType.LAVA_OCEAN;
        }

        for (Tile t : tiles) {
            boolean boundary = isPlateBoundary(t);
            double v = t.volcanism / 100.0;
            double stress = t.tectonicStress / 100.0;
            double score = v * 0.6 + stress * 0.4 + (boundary ? 0.2 : 0.0);

            if (score > 0.7 && rnd.nextDouble() < 0.6) {
                t.surfaceType = SurfaceType.VOLCANO;
            } else if (score > 0.5 && rnd.nextDouble() < 0.6) {
                t.surfaceType = SurfaceType.VOLCANIC_FIELD;
            } else if (score > 0.3 && rnd.nextDouble() < 0.5) {
                t.surfaceType = SurfaceType.LAVA_PLAINS;
            } else if (rnd.nextDouble() < 0.05) {
                t.surfaceType = SurfaceType.LAVA_ISLANDS;
            }
        }
    }

    private boolean isPlateBoundary(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (n.plateId != t.plateId) return true;
        }
        return false;
    }
}
