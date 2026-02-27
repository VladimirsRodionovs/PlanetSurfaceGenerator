package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.PlanetConfig;

import java.util.List;

public class IceGenerator {

    public void apply(List<Tile> tiles, PlanetConfig planet) {
        for (Tile t : tiles) {
            double warm = Double.isNaN(t.tempMax) ? t.temperature : t.tempMax;
            double cold = Double.isNaN(t.tempMin) ? t.temperature : t.tempMin;

            boolean frozen = warm < 0.0 || cold < -10.0;

            if (t.surfaceType == SurfaceType.OCEAN) {
                if (warm < 0.0) {
                    t.surfaceType = SurfaceType.ICE_OCEAN;
                }
                continue;
            }

            if (frozen) {
                if (cold < -20.0) {
                    t.surfaceType = SurfaceType.ICE_SHEET;
                } else if (cold < -5.0) {
                    t.surfaceType = SurfaceType.GLACIER;
                } else {
                    t.surfaceType = SurfaceType.TUNDRA;
                }
            } else if (cold < 0.0) {
                t.surfaceType = SurfaceType.PERMAFROST;
            }
        }

        // подповерхностный океан: примерная толщина льда
        int base = 0;
        if (planet.waterCoverageOrdinal >= 2) {
            base = 500;
        }
        if (planet.volcanism > 50) {
            base -= 200;
        }
        if (planet.tidalLocked) {
            base -= 150;
        }
        if (planet.meanTemperatureK < 200) {
            base += 500;
        }
        planet.subsurfaceIceThicknessMeters = Math.max(0, base);

        // летучие льды для ICE_VOLATILE
        if (planet.methaneIceFrac + planet.ammoniaIceFrac > 0.05) {
            for (Tile t : tiles) {
                double cold = Double.isNaN(t.tempMin) ? t.temperature : t.tempMin;
                if (cold > -20) continue;

                if (planet.methaneIceFrac > planet.ammoniaIceFrac && cold < -30) {
                    t.surfaceType = SurfaceType.METHANE_ICE;
                } else if (planet.ammoniaIceFrac >= planet.methaneIceFrac && cold < -25) {
                    t.surfaceType = SurfaceType.AMMONIA_ICE;
                } else if (cold < -40) {
                    t.surfaceType = SurfaceType.CO2_ICE;
                }
            }
        }
    }
}
