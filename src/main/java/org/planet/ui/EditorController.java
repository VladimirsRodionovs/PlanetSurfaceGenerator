package org.planet.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseEvent;
import org.planet.core.model.Tile;
import org.planet.core.model.SurfaceType;

import java.util.List;

public class EditorController {

    private final Canvas canvas;
    private final List<Tile> tiles;

    public EditorController(Canvas canvas, List<Tile> tiles) {
        this.canvas = canvas;
        this.tiles = tiles;
    }

    public void enable() {
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            Tile tile = findNearest(e.getX(), e.getY());
            if (tile != null) {
                tile.surfaceType = next(tile.surfaceType);
            }
        });
    }

    private Tile findNearest(double x, double y) {
        Tile best = null;
        double bestDist = Double.MAX_VALUE;

        for (Tile t : tiles) {
            double tx = (t.lon / 100_000.0 + 180) / 360 * canvas.getWidth();
            double ty = (90 - t.lat / 100_000.0) / 180 * canvas.getHeight();

            double d = Math.hypot(tx - x, ty - y);
            if (d < bestDist && d < 6) {
                bestDist = d;
                best = t;
            }
        }
        return best;
    }

    private SurfaceType next(SurfaceType t) {
        SurfaceType[] vals = SurfaceType.values();
        return vals[(t.ordinal() + 1) % vals.length];
    }
}
