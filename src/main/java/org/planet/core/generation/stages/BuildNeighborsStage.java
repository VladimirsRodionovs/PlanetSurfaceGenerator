package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.WorldContext;
import org.planet.core.topology.IcosaNeighborsBuilder;
import org.planet.core.generation.StageId;


public class BuildNeighborsStage implements GenerationStage {

    // Оставлено для совместимости со старым вызовом new BuildNeighborsStage(threshold)
    public BuildNeighborsStage(double ignoredThreshold) {
        // threshold больше не нужен: топология фиксированная (12 пентов + хексы)
    }

    // Можно оставить и безаргументный, если хочешь
    public BuildNeighborsStage() {
    }

    @Override
    public String name() {
        return "Build Neighbors (Icosa topology)";
    }

    @Override
    public void apply(WorldContext ctx) {
        new IcosaNeighborsBuilder().build(ctx.tiles);
    }

    @Override
    public StageId id() {
        return StageId.NEIGHBORS;
    }

}
