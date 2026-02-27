package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.PlanetGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class BaseSurfaceStage implements GenerationStage {

    @Override
    public String name() {
        return "Base Surface";
    }

    @Override
    public void apply(WorldContext ctx) {
        new PlanetGenerator(ctx.settings).generateBaseSurface(ctx.tiles, ctx.planet);
        // snapshot base surface types before later water classification overwrites them
        ctx.baseSurfaceType = new int[ctx.tiles.size()];
        for (int i = 0; i < ctx.tiles.size(); i++) {
            ctx.baseSurfaceType[i] = ctx.tiles.get(i).surfaceType.ordinal();
        }
    }

    @Override
    public StageId id() {
        return StageId.BASE_SURFACE;
    }

}
