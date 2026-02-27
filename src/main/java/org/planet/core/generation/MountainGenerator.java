package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.SurfaceType;

import java.util.*;

public class MountainGenerator {

    private final Random random;

    public MountainGenerator(long seed) {
        this.random = new Random(seed);
    }

    public void generateMountains(List<Tile> tiles, double gravity) {
        double g = Math.max(0.25, gravity);
        for (Tile t : tiles) {

            boolean plateBoundary = false;
            for (Tile n : t.neighbors) {
                if (n.plateId != t.plateId) {
                    plateBoundary = true;
                    break;
                }
            }

            if (!plateBoundary) continue;

            // При 1g допускаем пики 70+ (7+ км), ниже g -> выше рельеф.
            double gravityScale = Math.pow(1.0 / g, 0.70);
            int localCap = clampInt((int) Math.round(95.0 * gravityScale), 35, 180);
            int stressBoost = Math.max(0, t.tectonicStress - 30) / 2;
            int candidateHeight = random.nextInt(localCap + 1) + stressBoost;
            int height = Math.min(220, candidateHeight);

            if (height > 0 && t.surfaceType != SurfaceType.OCEAN) {
                // Не затираем орогенез, а наращиваем существующий рельеф.
                int newElevation = Math.max(t.elevation, height);
                t.elevation = clampInt(newElevation, 0, 255);

                if (height >= Math.max(45, (int) Math.round(localCap * 0.65))) {
                    t.surfaceType = SurfaceType.MOUNTAINS;
                } else {
                    t.surfaceType = SurfaceType.HILLS;
                }
            }
        }
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
