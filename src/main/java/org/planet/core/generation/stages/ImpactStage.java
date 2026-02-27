package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.ImpactGenerator;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;

public class ImpactStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.IMPACTS;
    }

    @Override
    public String name() {
        return "Impacts";
    }

    @Override
    public void apply(WorldContext ctx) {
        new ImpactGenerator().apply(ctx.tiles, ctx.planet, ctx.settings.seed);
    }
}
