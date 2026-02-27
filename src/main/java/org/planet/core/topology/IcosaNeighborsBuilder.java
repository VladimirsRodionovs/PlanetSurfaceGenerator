package org.planet.core.topology;

import org.planet.core.model.Tile;

import java.util.*;

/**
 * Строит топологию для "сферы из 12 пентов + остальных хексов".
 * Требование:
 * - tile id 0..11: ровно 5 соседей
 * - остальные: ровно 6 соседей
 *
 * Строим по принципу k ближайших по расстоянию на сфере (по 3D-вектору из lat/lon),
 * затем симметризуем и приводим к точному количеству.
 */
public class IcosaNeighborsBuilder {

    public void build(List<Tile> tiles) {
        // 1) Предварительно: очистим соседей
        for (Tile t : tiles) {
            t.neighbors.clear();
        }

        // 2) Считаем координаты на единичной сфере
        Map<Tile, double[]> vec = new HashMap<>();
        for (Tile t : tiles) {
            vec.put(t, toUnitVec(t.lat, t.lon));
        }

        // 3) Для каждого тайла: берём k ближайших (по угловой/евклидовой метрике на сфере)
        Map<Tile, List<Tile>> knn = new HashMap<>();
        for (Tile t : tiles) {
            int k = expectedDegree(t.id);
            List<Tile> nearest = kNearest(t, tiles, vec, k);
            knn.put(t, nearest);
        }

        // 4) Симметризация: если t выбрал u, то u тоже должен иметь t
        Map<Tile, Set<Tile>> sym = new HashMap<>();
        for (Tile t : tiles) sym.put(t, new HashSet<>());

        for (Tile t : tiles) {
            for (Tile u : knn.get(t)) {
                sym.get(t).add(u);
                sym.get(u).add(t);
            }
        }

        // 5) Приведение к точной степени (5 или 6) с приоритетом ближних
        for (Tile t : tiles) {
            int want = expectedDegree(t.id);

            List<Tile> candidates = new ArrayList<>(sym.get(t));
            candidates.sort((a, b) -> compareByDistThenId(a, b, vec.get(t), vec));

            if (candidates.size() < want) {
                // Это означает, что входные точки не соответствуют ожидаемой топологии
                // (или слишком "ломаные" координаты). Падаем сразу, это важно.
                throw new IllegalStateException("Not enough neighbor candidates for tile id=" + t.id
                        + " have=" + candidates.size() + " want=" + want);
            }

            // Обрезаем до want
            List<Tile> chosen = candidates.subList(0, want);
            t.neighbors.clear();
            t.neighbors.addAll(chosen);
        }

        // 6) Финальная симметричная коррекция:
        // после обрезки симметрия может нарушиться. Делаем ещё один проход:
        // если A содержит B, но B не содержит A — добавляем A в B и убираем самый дальний, чтобы сохранить размер.
        boolean changed;
        int guard = 0;
        do {
            changed = false;
            guard++;
            if (guard > 10_000) {
                throw new IllegalStateException("Neighbor symmetrization did not converge");
            }

            for (Tile a : tiles) {
                for (Tile b : new ArrayList<>(a.neighbors)) {
                    if (!b.neighbors.contains(a)) {
                        // добавить a в b
                        int wantB = expectedDegree(b.id);
                        b.neighbors.add(a);

                        // если перелимит — убрать самый дальний от b
                        if (b.neighbors.size() > wantB) {
                            b.neighbors.sort((x, y) -> compareByDistThenId(x, y, vec.get(b), vec));
                            // удаляем последний (самый дальний)
                            Tile removed = b.neighbors.remove(b.neighbors.size() - 1);

                            // если вдруг удалили a, то симметрия не улучшилась — но это допустимо, продолжим итерации
                        }
                        changed = true;
                    }
                }
            }
        } while (changed);

        // 7) Последняя проверка размеров (на всякий)
        for (Tile t : tiles) {
            int want = expectedDegree(t.id);
            if (t.neighbors.size() != want) {
                throw new IllegalStateException("Wrong neighbor count after build for tile id=" + t.id
                        + " have=" + t.neighbors.size() + " want=" + want);
            }
        }
    }

    private int expectedDegree(int id) {
        return (id >= 0 && id <= 11) ? 5 : 6;
    }

    private List<Tile> kNearest(Tile t, List<Tile> tiles, Map<Tile, double[]> vec, int k) {
        double[] vt = vec.get(t);

        // max-heap размера k
        PriorityQueue<Tile> pq = new PriorityQueue<>(
                (a, b) -> -compareByDistThenId(a, b, vt, vec)
        );

        for (Tile u : tiles) {
            if (u == t) continue;

            if (pq.size() < k) {
                pq.add(u);
            } else {
                double du = dist2(vt, vec.get(u));
                double dmax = dist2(vt, vec.get(pq.peek()));
                if (du < dmax) {
                    pq.poll();
                    pq.add(u);
                }
            }
        }

        List<Tile> res = new ArrayList<>(pq);
        res.sort((a, b) -> compareByDistThenId(a, b, vt, vec));
        return res;
    }

    private int compareByDistThenId(Tile a, Tile b, double[] origin, Map<Tile, double[]> vec) {
        double da = dist2(origin, vec.get(a));
        double db = dist2(origin, vec.get(b));
        int cmp = Double.compare(da, db);
        if (cmp != 0) return cmp;
        return Integer.compare(a.id, b.id);
    }

    private double[] toUnitVec(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);

        double x = Math.cos(lat) * Math.cos(lon);
        double y = Math.cos(lat) * Math.sin(lon);
        double z = Math.sin(lat);
        return new double[]{x, y, z};
    }

    private double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return dx*dx + dy*dy + dz*dz;
    }
}
