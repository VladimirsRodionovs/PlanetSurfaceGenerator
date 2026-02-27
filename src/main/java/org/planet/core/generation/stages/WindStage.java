package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.WindGenerator;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.StageId;


public class WindStage implements GenerationStage {

    private final double alpha;
    private final double beta;
    private final double gamma;

    public WindStage(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    @Override
    public String name() {
        return "Wind";
    }

    @Override
    public void apply(WorldContext ctx) {
        WindGenerator wind = new WindGenerator(alpha, beta, gamma);
        wind.generateWind(ctx.tiles, ctx.planet, 0.0, ctx.settings.seed, ctx.settings.climateModelMode);
        // Снимаем слепок климата сразу после ветра
        org.planet.core.generation.ClimateSampler.sample(ctx.tiles, ctx.planet);
    }

    @Override
    public StageId id() {
        return StageId.WIND;
    }
}
