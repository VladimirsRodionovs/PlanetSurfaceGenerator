package org.planet.core.generation;

import org.planet.core.model.*;
import java.util.*;

public class TectonicStressGenerator {

    public void generateStress(
            List<Tile> tiles,
            List<TectonicPlate> plates
    ) {


        for (Tile t : tiles) {
            t.tectonicStress = 0;

            List<Tile> neigh = t.neighbors;
            if (neigh == null) continue;

            for (Tile n : neigh) {
                if (n.plateId != t.plateId) {

                    TectonicPlate p1 = plates.get(t.plateId);
                    TectonicPlate p2 = plates.get(n.plateId);

                    double dot =
                            p1.dx * p2.dx +
                                    p1.dy * p2.dy;

                    int stress;

                    if (dot < -0.3) {
                        // столкновение плит
                        stress = 80;
                    } else if (dot > 0.3) {
                        // растяжение
                        stress = 40;
                    } else {
                        // сдвиг
                        stress = 60;
                    }

                    t.tectonicStress =
                            Math.max(t.tectonicStress, stress);
                }
            }
        }
    }
}
