package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.SurfaceType;

import java.util.*;

public class VolcanoGenerator {

    private final Random random;
    int count = 0;

    public VolcanoGenerator(long seed) {
        this.random = new Random(seed);
    }

    public void generateVolcanism(List<Tile> tiles, int volcanism) {



        for (Tile t : tiles) {

            boolean plateBoundary = false;
            for (Tile n : t.neighbors) {
                if (n.plateId != t.plateId) {
                    plateBoundary = true;
                    break;
                }
            }

            if (!plateBoundary) continue;

            int chance = volcanism / 2;

            if (random.nextInt(100) < chance) {

                t.volcanism = random.nextInt(100);

                if (t.volcanism > 70) {
                    t.surfaceType = SurfaceType.VOLCANIC;
                }
            }
            if (random.nextInt(100) < chance) {
                t.volcanism = random.nextInt(100);
                t.surfaceType = SurfaceType.VOLCANIC;
                count++;
            }
        }
        System.out.println("Volcano tiles: " + count);
    }

}
