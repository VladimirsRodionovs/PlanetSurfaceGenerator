package org.planet.core.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.planet.core.model.Tile;

import java.util.ArrayList;
import java.util.List;

public class HexDataEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String encode(List<Tile> tiles) {
        // top-level = массив тайлов
        List<Object> all = new ArrayList<>(tiles.size());

        for (Tile t : tiles) {
            int nCount = (t.neighbors == null) ? 0 : t.neighbors.size();
            int[] neigh = new int[nCount];
            for (int i = 0; i < nCount; i++) {
                neigh[i] = t.neighbors.get(i).id; // ты гарантируешь id == index
            }

            double windMag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);

            // surface type index:
            // ВАЖНО: если используешь texId() – пиши texId.
            // если ordinal() – пиши ordinal.
            int surfaceIdx = t.surfaceType.ordinal();

            List<Object> row = new ArrayList<>(9);
            row.add(t.id);
            row.add(nCount);
            row.add(neigh);
            row.add(t.lat);
            row.add(t.lon);
            row.add(surfaceIdx);
            row.add(t.temperature);
            row.add(t.elevation);
            row.add(windMag);

            all.add(row);
        }

        try {
            return MAPPER.writeValueAsString(all);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to encode HexData JSON", e);
        }
    }
}
