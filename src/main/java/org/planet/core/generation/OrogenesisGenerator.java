package org.planet.core.generation;

import org.planet.core.model.*;
import java.util.*;
import org.planet.core.model.config.PlanetConfig;

public class OrogenesisGenerator {

    public void applyOrogenesis(
            List<Tile> tiles,
            PlanetConfig planet
    ) {
        for (Tile t : tiles) {

            if (t.tectonicStress < 30) continue;

            int delta = t.tectonicStress / 20;

            // гравитация ограничивает высоту гор
            delta = (int)(delta / planet.gravity);

            t.elevation += delta;
        }
    }
}

