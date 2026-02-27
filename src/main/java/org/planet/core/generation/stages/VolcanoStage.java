package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.VolcanoGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class VolcanoStage implements GenerationStage {

    @Override
    public String name() {
        return "Volcanism";
    }

    @Override
    public void apply(WorldContext ctx) {
        VolcanoGenerator gen = new VolcanoGenerator(ctx.settings.seed);
        gen.generateVolcanism(ctx.tiles, ctx.planet.volcanism);
    }
    @Override
    public StageId id() {
        return StageId.VOLCANISM;
    }
}
