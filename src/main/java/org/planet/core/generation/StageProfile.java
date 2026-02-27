package org.planet.core.generation;

import java.util.EnumSet;
import java.util.Set;

public class StageProfile {
    private final Set<StageId> enabled;

    private StageProfile(Set<StageId> enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled(StageId id) {
        return enabled.contains(id);
    }

    public static StageProfile upToWind() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM,
                StageId.CLIMATE,
                StageId.WIND,
                StageId.WATER_REBALANCE,
                StageId.WATER_CLASSIFY,
                StageId.SEASONAL_CLIMATE,
                StageId.RIVERS,
                StageId.RELIEF,
                StageId.RESOURCES
        ));
    }

    // Пример полезного профиля: только тектоника (без климата/ветра)
    public static StageProfile tectonicsOnly() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM
        ));
    }

    public static StageProfile upToErosion() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM,
                StageId.CLIMATE,
                StageId.WIND,
                StageId.WATER_REBALANCE,
                StageId.EROSION,
                StageId.WATER_CLASSIFY,
                StageId.SEASONAL_CLIMATE,
                StageId.RIVERS,
                StageId.RELIEF,
                StageId.BIOMES,
                StageId.RESOURCES
        ));
    }

    public static StageProfile upToClimate() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM,
                StageId.CLIMATE,
                StageId.SEASONAL_CLIMATE

        ));
    }

    // Без тектоники, только базовая поверхность + климат/ветер/эрозия
    public static StageProfile surfaceAndClimate() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.CLIMATE,
                StageId.WIND,
                StageId.WATER_REBALANCE,
                StageId.ICE,
                StageId.EROSION,
                StageId.WATER_CLASSIFY,
                StageId.SEASONAL_CLIMATE,
                StageId.RIVERS,
                StageId.RELIEF,
                StageId.BIOMES,
                StageId.RESOURCES
        ));
    }

    public static StageProfile upToErosionWithRecalc() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM,
                StageId.CLIMATE,
                StageId.WIND,
                StageId.WATER_REBALANCE,
                StageId.EROSION,
                StageId.CLIMATE_RECALC,
                StageId.SEASONAL_CLIMATE,
                StageId.ICE,
                StageId.WATER_CLASSIFY,
                StageId.RIVERS,
                StageId.RELIEF,
                StageId.BIOMES,
                StageId.RESOURCES
        ));
    }

    public static StageProfile airless() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM,
                StageId.CLIMATE,
                StageId.IMPACTS,
                StageId.WATER_CLASSIFY,
                StageId.RESOURCES
        ));
    }

    public static StageProfile iceWorld(boolean withRecalc) {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.CLIMATE,
                StageId.WIND,
                StageId.WATER_REBALANCE,
                StageId.ICE,
                StageId.EROSION,
                withRecalc ? StageId.CLIMATE_RECALC : StageId.CLIMATE,
                StageId.WATER_CLASSIFY,
                StageId.SEASONAL_CLIMATE,
                StageId.RIVERS,
                StageId.RELIEF,
                StageId.BIOMES,
                StageId.RESOURCES
        ));
    }

    public static StageProfile lavaWorld() {
        return new StageProfile(EnumSet.of(
                StageId.NEIGHBORS,
                StageId.BASE_SURFACE,
                StageId.PLATES,
                StageId.STRESS,
                StageId.OROGENESIS,
                StageId.MOUNTAINS,
                StageId.VOLCANISM,
                StageId.LAVA,
                StageId.WATER_CLASSIFY,
                StageId.RELIEF,
                StageId.BIOMES,
                StageId.RESOURCES
        ));
    }

}
