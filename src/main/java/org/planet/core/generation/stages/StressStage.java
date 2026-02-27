package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.TectonicStressGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class StressStage implements GenerationStage {

    @Override
    public String name() {
        return "Tectonic Stress";
    }

    @Override
    public void apply(WorldContext ctx) {
        if (ctx.plates == null) {
            throw new IllegalStateException("Plates not generated yet (ctx.plates is null)");
        }
        new TectonicStressGenerator().generateStress(ctx.tiles, ctx.plates);
    }

    @Override
    public StageId id() {
        return StageId.STRESS;
    }
}
