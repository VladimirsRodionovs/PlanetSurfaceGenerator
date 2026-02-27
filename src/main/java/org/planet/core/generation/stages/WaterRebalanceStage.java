package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;
import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WaterRebalanceStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.WATER_REBALANCE;
    }

    @Override
    public String name() {
        return "Water Rebalance";
    }

    @Override
    public void apply(WorldContext ctx) {
        // применяем только для миров с водой и атмосферой
        if (!ctx.planet.hasAtmosphere) return;
        if (ctx.planet.waterCoverageOrdinal < 1) return;
        if (ctx.planet.waterCoverageOrdinal >= 3) return; // озёра/сухие/моря

        double dryThreshold = 25.0;
        double wetThreshold = 45.0;

        List<Tile> dryWater = new ArrayList<>();
        List<Tile> wetLand = new ArrayList<>();

        for (Tile t : ctx.tiles) {
            double p = Double.isNaN(t.precipAvg) ? 0.0 : t.precipAvg;
            if (t.surfaceType == SurfaceType.OCEAN && p < dryThreshold) {
                dryWater.add(t);
            } else if (t.surfaceType != SurfaceType.OCEAN && p > wetThreshold) {
                wetLand.add(t);
            }
        }

        if (dryWater.isEmpty() || wetLand.isEmpty()) return;

        Random rnd = new Random(ctx.settings.seed + 1337);
        int moves = Math.min(dryWater.size(), wetLand.size());
        for (int i = 0; i < moves; i++) {
            Tile from = dryWater.get(rnd.nextInt(dryWater.size()));
            Tile to = wetLand.get(rnd.nextInt(wetLand.size()));

            if (from == to) continue;

            from.surfaceType = SurfaceType.PLAINS;
            to.surfaceType = SurfaceType.OCEAN;
        }
    }
}
