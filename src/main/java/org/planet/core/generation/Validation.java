package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;

import java.util.List;

public final class Validation {

    private Validation() {}

    public static void afterNeighbors(WorldContext ctx) {
        for (Tile t : ctx.tiles) {
            if (t.neighbors == null) {
                throw new IllegalStateException("Neighbors not initialized for tile id=" + t.id);
            }

            int want = (t.id >= 0 && t.id <= 11) ? 5 : 6;
            int have = t.neighbors.size();

            if (have != want) {
                throw new IllegalStateException("Wrong neighbor count for tile id=" + t.id
                        + " have=" + have + " want=" + want);
            }
        }
    }


    public static void afterBaseSurface(WorldContext ctx) {
        for (Tile t : ctx.tiles) {
            if (t.surfaceType == null || t.surfaceType == SurfaceType.UNKNOWN) {
                throw new IllegalStateException("SurfaceType UNKNOWN after BaseSurface for tile id=" + t.id);
            }
        }
    }

    public static void afterPlates(WorldContext ctx) {
        if (ctx.plates == null || ctx.plates.isEmpty()) {
            throw new IllegalStateException("ctx.plates is null/empty after Plates stage");
        }

        // plateId начинается с 0, поэтому проверяем иначе:
        // 1) что plateId попадает в допустимый диапазон [0, plates.size()-1]
        // 2) что использовалось хотя бы 2 разных plateId (иначе похоже на "всем назначили одну плиту")
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        int first = ctx.tiles.get(0).plateId;
        boolean anyDifferent = false;

        for (Tile t : ctx.tiles) {
            min = Math.min(min, t.plateId);
            max = Math.max(max, t.plateId);
            if (t.plateId != first) anyDifferent = true;
        }

        if (min < 0) {
            throw new IllegalStateException("Found negative plateId: min=" + min);
        }
        int allowedMax = ctx.plates.size() - 1;
        if (max > allowedMax) {
            throw new IllegalStateException("plateId out of range: max=" + max + " allowedMax=" + allowedMax);
        }

        if (!anyDifferent && ctx.tiles.size() > 1) {
            System.out.println("[WARN] All tiles have the same plateId=" + first + " after Plates stage");
        }
    }


    public static void afterStress(WorldContext ctx) {
        // stress у тебя int; допустим 0 валиден, но NaN тут нет. Проверим "не все одинаковые"
        int first = ctx.tiles.get(0).tectonicStress;
        boolean allSame = true;
        for (Tile t : ctx.tiles) {
            if (t.tectonicStress != first) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            // Не ошибка, но сильный сигнал. Можно сделать warn, но у нас пока без логгера.
            System.out.println("[WARN] TectonicStress seems constant across tiles: " + first);
        }
    }

    public static void afterOrogenesis(WorldContext ctx) {
        // elevation допускает 0, но не должна быть везде 0 после орогенеза на активной планете
        int first = ctx.tiles.get(0).elevation;
        boolean allSame = true;
        for (Tile t : ctx.tiles) {
            if (t.elevation != first) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            System.out.println("[WARN] Elevation seems constant after Orogenesis: " + first);
        }
    }

    public static void afterMountains(WorldContext ctx) {
        // Горы могут не появиться при слабых параметрах, поэтому только мягкое предупреждение
        int max = Integer.MIN_VALUE;
        for (Tile t : ctx.tiles) {
            max = Math.max(max, t.elevation);
        }
        if (max <= 0) {
            System.out.println("[WARN] Max elevation after Mountains <= 0 (max=" + max + ")");
        }
    }

    public static void afterVolcanism(WorldContext ctx) {
        // Мягкая проверка: volcanism в диапазоне
        for (Tile t : ctx.tiles) {
            if (t.volcanism < 0 || t.volcanism > 100) {
                throw new IllegalStateException("Volcanism out of range for tile id=" + t.id + " volcanism=" + t.volcanism);
            }
        }
    }

    public static void afterClimate(WorldContext ctx) {
        // Температура/давление должны быть заполнены (у тебя pressure дефолт 1, поэтому проверим "не все 1")
        int firstP = ctx.tiles.get(0).pressure;
        boolean allSameP = true;
        for (Tile t : ctx.tiles) {
            if (t.pressure != firstP) { allSameP = false; break; }
        }
        if (allSameP) {
            System.out.println("[WARN] Pressure seems constant after Climate: " + firstP);
        }
    }

    public static void afterWind(WorldContext ctx) {
        // Сейчас у тебя баг (ветер нулевой/влага одинаковая). Поэтому это НЕ ошибка, а warn.
        double firstPrecip = ctx.tiles.get(0).precipAvg;
        boolean allSamePrecip = true;
        for (Tile t : ctx.tiles) {
            if (Double.compare(t.precipAvg, firstPrecip) != 0) {
                allSamePrecip = false;
                break;
            }
        }
        if (allSamePrecip) {
            System.out.println("[WARN] Precip seems constant after Wind: " + firstPrecip);
        }

        double firstWX = ctx.tiles.get(0).windX;
        double firstWY = ctx.tiles.get(0).windY;
        boolean allSameWind = true;
        for (Tile t : ctx.tiles) {
            if (Double.compare(t.windX, firstWX) != 0 || Double.compare(t.windY, firstWY) != 0) {
                allSameWind = false;
                break;
            }
        }
        if (allSameWind) {
            System.out.println("[WARN] Wind seems constant after Wind stage: windX=" + firstWX + " windY=" + firstWY);
        }
    }

    public static void afterErosion(WorldContext ctx) {
        for (Tile t : ctx.tiles) {
            if (t.elevation < 0 || t.elevation > 255) {
                throw new IllegalStateException("Elevation out of byte range after Erosion. tile id=" + t.id
                        + " elevation=" + t.elevation);
            }
        }
    }

}
