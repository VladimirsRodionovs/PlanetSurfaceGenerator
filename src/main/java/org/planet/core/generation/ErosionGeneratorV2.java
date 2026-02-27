package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;

import java.util.List;

/**
 * Эрозия (упрощённо-реалистичная):
 * - Thermal erosion: осыпание склонов (талус + перенос вниз)
 * - Water erosion: смыв в зависимости от осадков и уклона
 * - Wind erosion: шлифовка в сухих ветреных местах
 *
 * Работает на сетке из 5/6 соседей. Подходит для 100k тайлов.
 *
 * Ограничение: elevation должен оставаться в диапазоне 0..255 (под байт в БД).
 */
public class ErosionGeneratorV2 {

    private static final int ELEV_MIN = 0;
    private static final int ELEV_MAX = 255;

    public void erode(List<Tile> tiles, PlanetConfig planet, GeneratorSettings settings) {
        final int n = tiles.size();

        // Буферы изменений (double для аккуратного накопления)
        double[] dElev = new double[n];

        // Hard safety: incoming height can be out of byte bounds for extreme edited planets.
        // Normalize before erosion so validation after the stage cannot fail on legacy values.
        for (Tile t : tiles) {
            t.elevation = clampInt(t.elevation, ELEV_MIN, ELEV_MAX);
        }

        // Подготовка: если rockHardness не задан (NaN), выставим дефолт
        for (Tile t : tiles) {
            if (Double.isNaN(t.rockHardness)) t.rockHardness = 0.5;
        }

        // Можно один раз приблизительно выставить твёрдость по текущим данным:
        // континентальная плита обычно "жёстче", высокая вулканичность тоже повышает (базальты).
        // Если не хочешь авто-оценку — закомментируй.
        seedHardnessFromTectonics(tiles);

        double g = Math.max(0.1, planet.gravity); // защита от деления
        // Чем выше g, тем "легче" осыпание (меньше допустимый уклон)
        double talus = settings.erosionTalusBase / Math.sqrt(g);

        for (int iter = 0; iter < settings.erosionIterations; iter++) {
            // обнуляем буфер
            for (int i = 0; i < n; i++) dElev[i] = 0.0;

            // 1) Thermal erosion + Water erosion + Wind erosion
            for (int i = 0; i < n; i++) {
                Tile t = tiles.get(i);

                // Океан (sea level) мы не эродируем как сушу.
                // Можно позже сделать отложение осадков на шельфе.
                if (t.surfaceType == SurfaceType.OCEAN || t.surfaceType == SurfaceType.LAVA_OCEAN) {
                    continue;
                }

                if (t.neighbors == null || t.neighbors.isEmpty()) continue;

                // находим самого низкого соседа (куда стекает/осыпается)
                Tile low = null;
                int lowElev = Integer.MAX_VALUE;

                for (Tile nb : t.neighbors) {
                    if (nb.elevation < lowElev) {
                        lowElev = nb.elevation;
                        low = nb;
                    }
                }
                if (low == null) continue;

                int elev = t.elevation;
                int delta = elev - lowElev;
                if (delta <= 0) {
                    // нет уклона вниз
                    continue;
                }

                // Нормируем твёрдость: 0..1
                double hardness = clamp01(t.rockHardness);

                // Чем мягче порода, тем сильнее процессы
                double mobility = 1.0 - hardness; // 1 = мягко, 0 = твёрдо

                // ---- Thermal: осыпание склонов ----
                double thermalTransfer = 0.0;
                if (delta > talus) {
                    thermalTransfer = (delta - talus) * settings.erosionThermalK * mobility;
                }

                // ---- Water: смыв водой ----
                // waterPower ~ precip * slope
                double precip = clamp(t.precipAvg, 0.0, 100.0);
                double slope = delta; // в единицах elevation
                double waterPower = (precip / 100.0) * slope;

                // При большей g уменьшим размыв чуть-чуть (упрощённо)
                double waterErode = settings.erosionWaterK * waterPower * (1.0 / g) * mobility;

                // ---- Wind: в сухих ветреных местах ----
                double windMag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
                double dryness = 1.0 - (precip / 100.0); // 1 = сухо
                double windErode = settings.erosionWindK * windMag * dryness * mobility;

                // Суммарно "снимаем" материал с t
                double totalErode = thermalTransfer + waterErode + windErode;
                if (totalErode <= 0) continue;

                // ограничим, чтобы за итерацию не снести слишком много
                // (важно для устойчивости)
                double maxPerStep = Math.max(1.0, elev * 0.10); // не больше 10% высоты за шаг
                totalErode = Math.min(totalErode, maxPerStep);

                // Сколько отложим вниз (часть снятого оседает в низине)
                double deposit = (thermalTransfer + waterErode) * settings.erosionDepositionK;
                // ветер считаем "уносит" (не депонируем)
                deposit = Math.min(deposit, totalErode);

                // Применяем в буфер:
                dElev[i] -= totalErode;
                // депозит соседу
                int j = low.id; // ВАЖНО: предполагаем id == index. Если нет — см. примечание ниже.
                if (j >= 0 && j < n) {
                    dElev[j] += deposit;
                } else {
                    // Если id не совпадает с индексом, мы не депонируем, чтобы не ломать.
                    // Лучше потом сделать "id->index map".
                }
            }

            // 2) Применяем изменения и клампим 0..255
            for (int i = 0; i < n; i++) {
                Tile t = tiles.get(i);
                int newElev = (int) Math.round(t.elevation + dElev[i]);
                t.elevation = clampInt(newElev, ELEV_MIN, ELEV_MAX);
            }

            // 3) Каждые несколько итераций обновим SurfaceType по высоте
            if ((iter + 1) % 5 == 0) {
                updateReliefTypes(tiles, settings);
            }
        }

        // Финальное обновление типов
        updateReliefTypes(tiles, settings);
    }

    private void updateReliefTypes(List<Tile> tiles, GeneratorSettings settings) {
        for (Tile t : tiles) {
            if (t.surfaceType == SurfaceType.OCEAN || t.surfaceType == SurfaceType.LAVA_OCEAN) continue;

            int e = t.elevation;

            // Если это уже "специальный" тип (например вулкан/лед), можно позже решать отдельно.
            // Пока делаем простую деградацию только для hills/mountains/plains.
            if (e >= settings.mountainMinElevation) {
                // оставляем/поднимаем до гор только если уже было что-то подходящее
                if (t.surfaceType == SurfaceType.HILLS || t.surfaceType == SurfaceType.PLAINS || t.surfaceType == SurfaceType.MOUNTAINS) {
                    t.surfaceType = SurfaceType.MOUNTAINS;
                }
            } else if (e >= settings.hillMinElevation) {
                if (t.surfaceType == SurfaceType.PLAINS || t.surfaceType == SurfaceType.HILLS || t.surfaceType == SurfaceType.MOUNTAINS) {
                    t.surfaceType = SurfaceType.HILLS;
                }
            } else {
                if (t.surfaceType == SurfaceType.HILLS || t.surfaceType == SurfaceType.MOUNTAINS) {
                    t.surfaceType = SurfaceType.PLAINS;
                }
            }
        }
    }

    private void seedHardnessFromTectonics(List<Tile> tiles) {
        for (Tile t : tiles) {
            // базово: континентальная кора твёрже
            double h = (t.plateType == 1) ? 0.65 : 0.45;

            // вулканизм даёт базальты/магматиты → чуть твёрже
            h += (t.volcanism / 100.0) * 0.20;

            // ледяные места можно сделать "мягче" для абразии, но пока не трогаем
            t.rockHardness = clamp01(h);
        }
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
