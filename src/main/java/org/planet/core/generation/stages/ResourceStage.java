package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.ResourceGenerator;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;

public class ResourceStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.RESOURCES;
    }

    @Override
    public String name() {
        return "Resources";
    }

    @Override
    public void apply(WorldContext ctx) {
        new ResourceGenerator().generate(ctx.tiles, ctx.planet, ctx.settings);
    }
}
