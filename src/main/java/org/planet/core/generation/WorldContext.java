package org.planet.core.generation;

import org.planet.core.model.Tile;
import org.planet.core.model.TectonicPlate;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;

import java.util.List;
import java.util.Random;

/**
 * Контекст одной генерации планеты (один запуск = один контекст).
 * Здесь лежит всё, чем пользуются стадии, чтобы не таскать много параметров.
 */
public class WorldContext {

    public final List<Tile> tiles;
    public final PlanetConfig planet;
    public final GeneratorSettings settings;

    /** Стабильный RNG для генерации (один на запуск). */
    public final Random rng;

    /** Базовый тип поверхности до водной классификации (по id тайла). */
    public int[] baseSurfaceType;

    /** Список плит появляется после PlateStage. До этого может быть null. */
    public List<TectonicPlate> plates;

    /** Сколько плит генерим. */
    public final int plateCount;

    public WorldContext(List<Tile> tiles, PlanetConfig planet, GeneratorSettings settings, int plateCount) {
        this.tiles = tiles;
        this.planet = planet;
        this.settings = settings;
        this.plateCount = plateCount;

        // Берём сид из settings (если он у тебя гарантированно проставляется)
        // Если settings.seed может быть 0/не задан — поставь тут запасной.
        this.rng = new Random(settings.seed);
    }
}
