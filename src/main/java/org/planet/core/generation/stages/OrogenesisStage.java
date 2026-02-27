package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.OrogenesisGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class OrogenesisStage implements GenerationStage {

    @Override
    public String name() {
        return "Orogenesis";
    }

    @Override
    public void apply(WorldContext ctx) {
        new OrogenesisGenerator().applyOrogenesis(ctx.tiles, ctx.planet);
    }

    @Override
    public StageId id() {
        return StageId.OROGENESIS;
    }
}
