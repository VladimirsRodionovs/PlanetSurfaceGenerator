package org.planet.core.model;

import java.util.ArrayList;
import java.util.List;
import org.planet.core.generation.ResourcePresence;

/**
 * Каноническая модель тайла.
 *
 * Правила проекта:
 * - Высота рельефа: только elevation (height удалён).
 * - Влага: только moisture (humidity удалён).
 * - Соседи: заполняются один раз (NeighborGraphBuilder) и дальше только читаются.
 *
 * Примечание: блок "ресурсы" сейчас legacy-минимум (будет переработан).
 */
public class Tile {

    // --- Идентификация ---
    public final int id;

    // --- География (градусы) ---
    public double lat;
    public double lon;

    // --- Топология ---
    public List<Tile> neighbors = new ArrayList<>();

    // --- Состояние поверхности ---
    public SurfaceType surfaceType = SurfaceType.UNKNOWN;

    // --- Тектоника ---
    public int plateId;
    /** 0 = океаническая, 1 = континентальная (текущий набросок) */
    public int plateType;
    /** 0–100 (условно) */
    public int tectonicStress;

    // --- Рельеф ---
    /** относительная высота (1 = 100 м) */
    public int elevation;
    /** исходная высота рельефа под водой (1 = 100 м); для суши = 0 */
    public int underwaterElevation = 0;
    /** 0–100 */
    public int volcanism;
    public boolean ice;

    // --- Атмосфера / климат ---
    /** °C */
    public int temperature;
    /** относительное давление (пример: 1000 = 1 атм) */
    public int pressure;

    // --- Вода / влага / ветер ---
    /** 0..100 % */
    public double moisture;
    /** атмосферная влага, г/кг */
    public double atmMoist = Double.NaN;
    public double windX;
    public double windY;

    // --- Климатические слепки ---
    public double tempMin = Double.NaN;
    public double tempMax = Double.NaN;
    public double windAvg = Double.NaN;
    public double windMax = Double.NaN;
    public double precipAvg = Double.NaN;
    public double evapAvg = Double.NaN;
    /** Физический поток осадков, кг/м^2/сутки (эквивалент мм/сутки). */
    public double precipKgM2Day = Double.NaN;
    /** Физический поток испарения, кг/м^2/сутки (эквивалент мм/сутки). */
    public double evapKgM2Day = Double.NaN;
    /** Поверхностный сток (осадки, не инфильтрованные в почву), кг/м^2/сутки. */
    public double surfaceRunoffKgM2Day = Double.NaN;
    /** Межсезонные физические потоки (годовой baseline перед сезонными прогонами). */
    public double precipKgM2DayInterseason = Double.NaN;
    public double evapKgM2DayInterseason = Double.NaN;
    public double surfaceRunoffKgM2DayInterseason = Double.NaN;
    /** Сезонные физические потоки: warm/cold снимки. */
    public double precipKgM2DayWarm = Double.NaN;
    public double evapKgM2DayWarm = Double.NaN;
    public double surfaceRunoffKgM2DayWarm = Double.NaN;
    public double precipKgM2DayCold = Double.NaN;
    public double evapKgM2DayCold = Double.NaN;
    public double surfaceRunoffKgM2DayCold = Double.NaN;
    /** Диагностика почвы за климатическое окно: стартовое значение (0..100). */
    public double soilStartDiag = Double.NaN;
    /** Диагностика почвы за климатическое окно: финальное значение (0..100). */
    public double soilEndDiag = Double.NaN;
    /** Вклад осадков в почвенную влагу (в индексных единицах 0..100 за окно). */
    public double soilFromPrecipDiag = Double.NaN;
    /** Потери почвенной влаги на испарение (в индексных единицах 0..100 за окно). */
    public double soilFromEvapDiag = Double.NaN;
    /** Чистый вклад латеральной диффузии почвы (в индексных единицах 0..100 за окно). */
    public double soilFromDiffDiag = Double.NaN;
    public int sunnyDays = 0;
    /** Солнечная генерация (потенциал), кВт*ч/сутки на тайл: межсезонье. */
    public double solarKwhDayInter = Double.NaN;
    /** Солнечная генерация (потенциал), кВт*ч/сутки на тайл: локальное лето. */
    public double solarKwhDayWarm = Double.NaN;
    /** Солнечная генерация (потенциал), кВт*ч/сутки на тайл: локальная зима. */
    public double solarKwhDayCold = Double.NaN;
    /** Приливной диапазон (отлив-прилив), м. */
    public double tidalRangeM = Double.NaN;
    /** Доминирующий приливной период, часы. */
    public double tidalPeriodHours = Double.NaN;
    /** Приливные циклы в сутки. */
    public double tidalCyclesPerDay = Double.NaN;
    /** Коэффициент локального усиления побережья (геометрия водного тела). */
    public double tidalCoastAmplification = Double.NaN;
    /** Характерный размер связанного водного тела перед/вокруг побережья, км. */
    public double tidalWaterBodyScaleKm = Double.NaN;

    // --- Сезоны ---
    public double tempWarm = Double.NaN;
    public double tempCold = Double.NaN;
    public double windWarm = Double.NaN;
    public double windCold = Double.NaN;
    public double windMaxWarm = Double.NaN;
    public double windMaxCold = Double.NaN;
    public double precipWarm = Double.NaN;
    public double precipCold = Double.NaN;
    public double evapWarm = Double.NaN;
    public double evapCold = Double.NaN;
    public int sunnyWarm = 0;
    public int sunnyCold = 0;
    public double moistureWarm = Double.NaN;
    public double moistureCold = Double.NaN;
    public double windXWarm = Double.NaN;
    public double windYWarm = Double.NaN;
    public double windXCold = Double.NaN;
    public double windYCold = Double.NaN;
    public double tempMinInterseason = Double.NaN;
    public double tempMaxInterseason = Double.NaN;
    public double tempMinWarm = Double.NaN;
    public double tempMaxWarm = Double.NaN;
    public double tempMinCold = Double.NaN;
    public double tempMaxCold = Double.NaN;
    // Локальные warm/cold для биомов: определяются по тайлу как max/min между двумя сезонными прогонами.
    public double biomeTempWarm = Double.NaN;
    public double biomeTempCold = Double.NaN;
    public double biomeTempInterseason = Double.NaN;
    public double biomePrecipWarm = Double.NaN;
    public double biomePrecipCold = Double.NaN;
    public double biomePrecipInterseason = Double.NaN;
    public double biomeEvapWarm = Double.NaN;
    public double biomeEvapCold = Double.NaN;
    public double biomeEvapInterseason = Double.NaN;
    public double biomeMoistureWarm = Double.NaN;
    public double biomeMoistureCold = Double.NaN;
    public double biomeMoistureInterseason = Double.NaN;
    /** 1 -> локальное warm взято из +tilt сезона, 0 -> из -tilt сезона, -1 -> неизвестно. */
    public int biomeWarmFromPositiveTilt = -1;
    /** 0 -> interseason, 1 -> local warm, 2 -> local cold. */
    public int biomePreferredSeason = -1;

    // --- Диагностика сезонной биом-классификации ---
    public double biomeTempRange = Double.NaN;
    public double biomeAiAnn = Double.NaN;
    public double biomeAiWarm = Double.NaN;
    public double biomeAiCold = Double.NaN;
    public double biomeMonsoon = Double.NaN;
    public BiomeRegime biomeRegime = BiomeRegime.UNKNOWN;
    public int biomeModifierMask = 0;

    // --- Гидрология ---
    public double riverFlow = 0.0; // 0..1 нормированный поток
    public int riverOrder = 0;     // 0..5
    public boolean isRiver = false;
    public int canyonDepth = 0;
    public int riverType = 0;      // 0..6
    public int riverTo = -1;       // index of downstream tile
    public RiverBaseType riverBaseType = RiverBaseType.NONE;
    /** Формат: BASE_TO_FROM_FROM... */
    public String riverTag = "";
    /** Расход реки, кг/с. */
    public double riverDischargeKgS = 0.0;
    /** Расход реки, т/с. */
    public double riverDischargeTps = 0.0;
    /** Потенциальный расход для маршрутизации, кг/с (кроме морей/океанов). */
    public double riverPotentialKgS = 0.0;
    public List<Integer> riverFrom = new ArrayList<>();

    // --- Ресурсы (legacy, будет полностью переработано) ---
    public int resourceType;
    public double resSurface;
    public double resMid;
    public double resDeep;
    public List<ResourcePresence> resources = new ArrayList<>();

    // --- Геология (упрощённо) ---
// 0..1: чем больше, тем порода твёрже (меньше выветривание)
    public double rockHardness = 0.5;


    public Tile(int id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;

        // дефолты
        this.pressure = 1;
        this.temperature = 0;
        this.moisture = Double.NaN;
    }
}
