package org.planet.core.generation.stages;

import org.planet.core.generation.ClimateGenerator;
import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class ClimateStage implements GenerationStage {

    @Override
    public String name() {
        return "Climate";
    }

    @Override
    public void apply(WorldContext ctx) {
        new ClimateGenerator().generate(ctx.tiles, ctx.planet);
    }

    @Override
    public StageId id() {
        return StageId.CLIMATE;
    }
}
