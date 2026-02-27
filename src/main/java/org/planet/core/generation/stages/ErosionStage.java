package org.planet.core.generation.stages;

import org.planet.core.generation.ErosionGeneratorV2;
import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;

public class ErosionStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.EROSION;
    }

    @Override
    public String name() {
        return "Erosion";
    }

    @Override
    public void apply(WorldContext ctx) {
        new ErosionGeneratorV2().erode(ctx.tiles, ctx.planet, ctx.settings);
    }
}
