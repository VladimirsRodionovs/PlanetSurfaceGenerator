package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.PlateGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class PlatesStage implements GenerationStage {

    @Override
    public String name() {
        return "Tectonic Plates";
    }

    @Override
    public void apply(WorldContext ctx) {
        // Важно: PlateGenerator в твоём проекте принимает seed.
        PlateGenerator gen = new PlateGenerator(ctx.settings.seed);
        ctx.plates = gen.generatePlates(ctx.tiles, ctx.planet, ctx.plateCount);
    }

    @Override
    public StageId id() {
        return StageId.PLATES;
    }
}
