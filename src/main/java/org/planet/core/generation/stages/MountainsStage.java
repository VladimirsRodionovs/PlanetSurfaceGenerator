package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.MountainGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class MountainsStage implements GenerationStage {

    @Override
    public String name() {
        return "Mountains";
    }

    @Override
    public void apply(WorldContext ctx) {
        MountainGenerator gen = new MountainGenerator(ctx.settings.seed);
        double gravity = (ctx.planet == null) ? 1.0 : ctx.planet.gravity;
        gen.generateMountains(ctx.tiles, gravity);
    }

    @Override
    public StageId id() {
        return StageId.MOUNTAINS;
    }
}
