package org.planet.core.generation.stages;

import org.planet.core.generation.ClimateGenerator;
import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.WindGenerator;
import org.planet.core.generation.ClimateSampler;

public class ClimateRecalcStage implements GenerationStage {

    private final double alpha;
    private final double beta;
    private final double gamma;

    public ClimateRecalcStage(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    @Override
    public StageId id() {
        return StageId.CLIMATE_RECALC;
    }

    @Override
    public String name() {
        return "Climate Recalc";
    }

    @Override
    public void apply(WorldContext ctx) {
        new ClimateGenerator().generate(ctx.tiles, ctx.planet);
        new WindGenerator(alpha, beta, gamma).generateWind(ctx.tiles, ctx.planet, 0.0, ctx.settings.seed, ctx.settings.climateModelMode);
        ClimateSampler.sample(ctx.tiles, ctx.planet);
    }
}
