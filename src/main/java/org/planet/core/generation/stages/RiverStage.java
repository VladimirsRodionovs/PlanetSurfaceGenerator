package org.planet.core.generation.stages;

import org.planet.core.generation.ClimateSampler;
import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.RiverGenerator;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WindGenerator;
import org.planet.core.generation.WorldContext;

public class RiverStage implements GenerationStage {

    private final double alpha;
    private final double beta;
    private final double gamma;

    public RiverStage(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    @Override
    public StageId id() {
        return StageId.RIVERS;
    }

    @Override
    public String name() {
        return "Rivers";
    }

    @Override
    public void apply(WorldContext ctx) {
        new RiverGenerator().generate(ctx.tiles, ctx.planet, ctx.settings.seed);
        // Recompute hydro-climate after rivers: updated surface moisture should affect evap/precip before biomes.
        new WindGenerator(alpha, beta, gamma).generateWind(ctx.tiles, ctx.planet, 0.0, ctx.settings.seed, ctx.settings.climateModelMode);
        ClimateSampler.sample(ctx.tiles, ctx.planet);
    }
}
