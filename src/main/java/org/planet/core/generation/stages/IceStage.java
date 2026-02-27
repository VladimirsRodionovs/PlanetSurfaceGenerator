package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.IceGenerator;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;

public class IceStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.ICE;
    }

    @Override
    public String name() {
        return "Ice";
    }

    @Override
    public void apply(WorldContext ctx) {
        new IceGenerator().apply(ctx.tiles, ctx.planet);
    }
}
