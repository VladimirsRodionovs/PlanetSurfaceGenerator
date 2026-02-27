package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WaterClassifierGenerator;
import org.planet.core.generation.WorldContext;

public class WaterClassifyStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.WATER_CLASSIFY;
    }

    @Override
    public String name() {
        return "Water Classify";
    }

    @Override
    public void apply(WorldContext ctx) {
        new WaterClassifierGenerator(ctx.settings.seed).apply(ctx.tiles, ctx.planet, ctx.baseSurfaceType);
    }
}
