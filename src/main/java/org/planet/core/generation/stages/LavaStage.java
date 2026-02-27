package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.LavaGenerator;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;

public class LavaStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.LAVA;
    }

    @Override
    public String name() {
        return "Lava World";
    }

    @Override
    public void apply(WorldContext ctx) {
        new LavaGenerator().apply(ctx.tiles, ctx.settings.seed);
    }
}
