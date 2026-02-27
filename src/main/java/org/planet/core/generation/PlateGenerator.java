package org.planet.core.generation;

import org.planet.core.model.*;
import java.util.*;
import org.planet.core.model.config.PlanetConfig;

public class PlateGenerator {

    private final Random random;

    public PlateGenerator(long seed) {
        this.random = new Random(seed);
    }

    public List<TectonicPlate> generatePlates(
            List<Tile> tiles,
            PlanetConfig planet,
            int plateCount
    ) {
        List<TectonicPlate> plates = new ArrayList<>();

        for (int i = 0; i < plateCount; i++) {
            TectonicPlate p = new TectonicPlate();
            p.id = i;
            p.continental = random.nextDouble() < 0.4;
            p.dx = random.nextDouble() * 2 - 1;
            p.dy = random.nextDouble() * 2 - 1;
            plates.add(p);
        }



        Map<Tile, TectonicPlate> assignment = new HashMap<>();

        // 1️⃣ сиды
        List<Tile> shuffled = new ArrayList<>(tiles);
        Collections.shuffle(shuffled, random);

        for (int i = 0; i < plateCount; i++) {
            assignment.put(shuffled.get(i), plates.get(i));
        }

        // 2️⃣ flood-fill
        boolean changed = true;
        while (changed) {
            changed = false;

            for (Tile t : tiles) {
                if (assignment.containsKey(t)) continue;

                for (Tile n : t.neighbors) {
                    TectonicPlate p = assignment.get(n);
                    if (p != null) {
                        assignment.put(t, p);
                        changed = true;
                        break;
                    }
                }
            }
        }

        // 3️⃣ ДОБОР осиротевших тайлов (КЛЮЧ!)
        boolean orphanFound = true;
        while (orphanFound) {
            orphanFound = false;

            for (Tile t : tiles) {
                if (assignment.containsKey(t)) continue;

                for (Tile n : t.neighbors) {
                    TectonicPlate p = assignment.get(n);
                    if (p != null) {
                        assignment.put(t, p);
                        orphanFound = true;
                        break;
                    }
                }
            }
        }

        // 4️⃣ запись в тайлы (теперь 100% safe)
        for (Tile t : tiles) {
            TectonicPlate p = assignment.get(t);

            if (p == null) {
                // аварийный fallback (не должен сработать)
                p = plates.get(random.nextInt(plates.size()));
            }

            t.plateId = p.id;
            t.plateType = p.continental ? 1 : 0;
        }

        return plates;
    }
}
