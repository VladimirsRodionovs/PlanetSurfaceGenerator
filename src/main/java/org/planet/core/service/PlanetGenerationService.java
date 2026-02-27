package org.planet.core.service;

import org.planet.core.generation.GenerationPipeline;
import org.planet.core.io.HexDataEncoder;
import org.planet.core.model.Tile;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;

import java.util.ArrayList;
import java.util.List;

public class PlanetGenerationService {

    private final List<Tile> baseTiles;          // шаблон (из tiles.csv)
    private final GenerationPipeline pipeline;

    public PlanetGenerationService(List<Tile> baseTiles, GenerationPipeline pipeline) {
        this.baseTiles = baseTiles;
        this.pipeline = pipeline;
    }

    // важно: генераторы мутируют tiles, поэтому делаем копию на каждый запуск
    public List<Tile> generate(PlanetConfig planet, GeneratorSettings settings, int plateCount) {
        List<Tile> tiles = deepCopyTiles(baseTiles);
        pipeline.run(tiles, planet, settings, plateCount);
        return tiles;
    }

    public String encodeHexData(List<Tile> tiles) {
        return HexDataEncoder.encode(tiles);
    }

    private List<Tile> deepCopyTiles(List<Tile> src) {
        List<Tile> out = new ArrayList<>(src.size());
        // предполагаем: Tile(int id, double lat, double lon)
        for (Tile t : src) {
            Tile c = new Tile(t.id, t.lat, t.lon);
            out.add(c);
        }
        return out;
    }
}
