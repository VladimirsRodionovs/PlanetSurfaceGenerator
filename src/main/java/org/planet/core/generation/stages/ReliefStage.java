package org.planet.core.generation.stages;

import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.ReliefClassifierGenerator;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;

public class ReliefStage implements GenerationStage {

    @Override
    public StageId id() {
        return StageId.RELIEF;
    }

    @Override
    public String name() {
        return "Relief Classify";
    }

    @Override
    public void apply(WorldContext ctx) {
        boolean hasLiquidWater = ctx.tiles.stream().anyMatch(t -> switch (t.surfaceType) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP -> true;
            default -> false;
        });
        if (!hasLiquidWater) {
            for (var t : ctx.tiles) {
                if (t.surfaceType == org.planet.core.model.SurfaceType.BASIN_SWAMP) {
                    t.surfaceType = org.planet.core.model.SurfaceType.BASIN_DRY;
                }
            }
        }
        new ReliefClassifierGenerator().apply(ctx.tiles, ctx.settings);
    }
}
