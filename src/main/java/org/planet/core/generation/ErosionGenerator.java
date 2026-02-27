package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.SurfaceType;

import java.util.*;

public class ErosionGenerator {

    public void erode(List<Tile> tiles) {



        for (Tile t : tiles) {

            if (t.elevation <= 0) continue;

            int erosionPower = 0;

            if (t.precipAvg > 60) erosionPower += 2;
            if (t.temperature > 25) erosionPower += 1;

            if (t.volcanism > 50) erosionPower -= 1;

            if (erosionPower > 0) {
                t.elevation = Math.max(0, t.elevation - erosionPower);
            }

            // понижение типа поверхности
            if (t.elevation == 0 && t.surfaceType == SurfaceType.MOUNTAINS) {
                t.surfaceType = SurfaceType.HILLS;
            }
            if (t.elevation == 0 && t.surfaceType == SurfaceType.HILLS) {
                t.surfaceType = SurfaceType.PLAINS;
            }
        }
    }
}
