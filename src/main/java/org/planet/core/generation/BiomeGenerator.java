package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.config.PlanetConfig;
import org.planet.core.model.SurfaceType;

public class BiomeGenerator {

    public void generateBiomes(
            Iterable<Tile> tiles,
            PlanetConfig planet
    ) {
        for (Tile t : tiles) {

            if (t.surfaceType == SurfaceType.VOLCANIC ||
                    t.surfaceType == SurfaceType.LAVA ||
                    planet.lavaWorld ||
                    t.surfaceType == SurfaceType.LAVA_OCEAN) {
                continue;
            }


            if (t.surfaceType == SurfaceType.OCEAN ||
                    t.surfaceType == SurfaceType.ICE) continue;

            if (t.volcanism > 70) {
                t.surfaceType = SurfaceType.LAVA;
                continue;
            }

            if (!planet.hasFlora) {
                if (t.temperature > 30) {
                    t.surfaceType = SurfaceType.DESERT;
                }
                continue;
            }

            // холодно
            if (t.temperature < 0) {
                t.surfaceType = SurfaceType.TUNDRA;
            }
            // сухо
            else if (t.precipAvg < 30) {
                t.surfaceType = SurfaceType.DESERT;
            }
            // лес
            else if (t.precipAvg > 60) {
                if (t.surfaceType == SurfaceType.HILLS)
                    t.surfaceType = SurfaceType.HILLS_FOREST;
                else
                    t.surfaceType = SurfaceType.PLAINS_FOREST;
            }
            // трава
            else {
                if (t.surfaceType == SurfaceType.HILLS)
                    t.surfaceType = SurfaceType.HILLS_GRASS;
                else
                    t.surfaceType = SurfaceType.PLAINS_GRASS;
            }
        }
    }
}
