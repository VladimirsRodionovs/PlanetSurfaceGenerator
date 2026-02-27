package org.planet.core.model;

public enum SurfaceType {
    UNKNOWN,

    OCEAN,
    ICE_OCEAN,
    LAVA_OCEAN,

    PLAINS,
    PLAINS_GRASS,
    PLAINS_FOREST,

    HILLS,
        HILLS_GRASS,
        HILLS_FOREST,

        MOUNTAINS,
        MOUNTAINS_SNOW,

    DESERT_SAND,
    DESERT_ROCKY,

    ICE,
    GLACIER,

    VOLCANIC_FIELD,
    VOLCANIC,
    VOLCANO,
    LAVA_PLAINS,
    LAVA_ISLANDS,

    DESERT,
    ROCKY_DESERT,
    TUNDRA,
    LAVA,

            // ВОДА
       // OCEAN,
        SHALLOW_SEA,
      //  ICE_OCEAN,

        // ЛЁД
        ICE_SHEET,
       // GLACIER,

        // ВУЛКАНИЗМ
      //  LAVA_OCEAN,
       // LAVA_PLAINS,
      //  VOLCANIC_FIELD,
        ACTIVE_VOLCANO,

        // РЕЛЬЕФ
      //  MOUNTAINS,
        HIGH_MOUNTAINS,
      //  HILLS,
        PLATEAU,
        HIGHLANDS,

        // ПУСТЫНИ
        SAND_DESERT,
        ROCK_DESERT,
        COLD_DESERT,

        // ЗЕМЛЕПОДОБНЫЕ
     //   PLAINS,
        GRASSLAND,
        SAVANNA,
        DRY_SAVANNA,
        FOREST,
        RAINFOREST,
        SWAMP,

        // ХОЛОДНЫЕ БИОМЫ
      //  TUNDRA,
        PERMAFROST,

        // БЕЗ АТМОСФЕРЫ
        REGOLITH,
    CRATERED_SURFACE

    ,
    // летучие льды
    METHANE_ICE,
    AMMONIA_ICE,
    CO2_ICE


    ,
    // ---- расширенные типы воды ----
    OPEN_WATER_SHALLOW,
    OPEN_WATER_DEEP,
    LAKE_FRESH,
    LAKE_SALT,
    COAST_SANDY,
    COAST_ROCKY,
    SEA_ICE_SHALLOW,
    SEA_ICE_DEEP,

    // ---- расширенные биомы рельефа ----
    HILLS_SAVANNA,
    HILLS_DRY_SAVANNA,
    HILLS_DESERT,
    HILLS_TUNDRA,
    MOUNTAINS_FOREST,
    MOUNTAINS_TUNDRA,
    MOUNTAINS_DESERT,
    MOUNTAINS_ALPINE,
    ALPINE_MEADOW,

    // ---- дополнительные формы рельефа ----
    RIDGE,
    CANYON,
    BASIN_FLOOR,

    // ---- рельеф + биомы (комбинированные) ----
    RIDGE_ROCK,
    RIDGE_SNOW,
    RIDGE_TUNDRA,
    RIDGE_GRASS,
    RIDGE_FOREST,
    RIDGE_DESERT,

    CANYON_ROCK,
    CANYON_TUNDRA,
    CANYON_GRASS,
    CANYON_FOREST,
    CANYON_DESERT,

    BASIN_GRASS,
    BASIN_FOREST,
    BASIN_DRY,
    BASIN_SWAMP,
    BASIN_TUNDRA,

    // ---- экстремальные водные поверхности ----
    STEAM_SEA,
    LAKE_BRINE,
    LAKE_ACID,

    // ---- rainforest with preserved relief ----
    HILLS_RAINFOREST,
    MOUNTAINS_RAINFOREST,

    // ---- hot mud wetlands ----
    MUD_SWAMP


}
