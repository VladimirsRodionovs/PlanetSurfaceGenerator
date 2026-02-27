package org.planet.core.io;

import org.planet.core.model.Tile;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class CsvTileLoader {

    public static List<Tile> load(String path) {
        List<Tile> tiles = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean first = true;

            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }

                String[] parts = line.split(",");

                int id = Integer.parseInt(parts[1].replace("\"", ""));
                double lat = Double.parseDouble(parts[2].replace("\"", ""));
                double lon = Double.parseDouble(parts[3].replace("\"", ""));

                tiles.add(new Tile(
                        id,
                        lat,
                        lon
                ));
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return tiles;
    }
}