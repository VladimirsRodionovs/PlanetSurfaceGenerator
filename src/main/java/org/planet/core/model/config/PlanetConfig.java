package org.planet.core.model.config;

import java.util.ArrayList;
import java.util.List;

public class PlanetConfig {

    public int averageTemperature; // °C
    public double gravity;         // g

    public boolean tidalLocked;
    public boolean hasFlora;

    public int waterAmount;        // 0–100
    public int lavaAmount;         // 0–100
    public int volcanism;          // 0–100

    public boolean lavaWorld;

    // --- Орбита и звезда ---
    public double stellarFlux;     // относительная освещённость
    public double axialTilt;       // градусы

    // --- Физика ---
    public int radius;             // км

    // --- Атмосфера ---
    public int atmosphericPressure; // 0–100
    public int atmosphericDensity;  // влияет на перенос
    public int heatCapacity;        // сглаживание температур

    // --- Вода ---
    public int initialWaterCoverage; // % поверхности (ориентир)

    // --- Геология ---
    public int tectonicActivity;   // 0–100

    public boolean hasAtmosphere;

    // Старое поле оставляем пока как есть (вдруг где-то используется),
    // но для ветра мы договорились использовать rotationPeriodHours.

    // ✅ НОВОЕ: период вращения планеты (в часах)
    // Пример: Земля = 24, Юпитер ~ 10, медленная планета = 100+
    public double rotationPeriodHours = 24.0;
    public double rotationSpeed; // ObjectRotationSpeed (units from DB)
    public int rotationPrograde = 1; // 1 prograde, 0 retrograde

    // поля, которые у тебя реально используются в Main/Climate и т.д.
    public double radiusKm;
    public double meanTemperature;     // °C
    public double atmosphereDensity;   // 0..2 (1 = Earth)

    // --- Доп. климатические поля из БД ---
    public double meanTemperatureK;
    public double minTemperatureK;
    public double maxTemperatureK;
    public double equilibriumTemperatureK;
    public double greenhouseDeltaK;

    // --- Состав поверхности (из PRes) ---
    public double methaneIceFrac;
    public double ammoniaIceFrac;
    public double organicsFrac;

    // --- Масса и вода (из БД) ---
    public double massEarth;
    public int waterCoverageOrdinal;
    public double waterGelKm;
    public double fracIron;
    public double fracRock;
    public double fracIce;

    // --- Биосфера (из БД) ---
    public int biosphereProvenance;
    public int biosphereSurfaceStatus;
    public int biosphereMicrobialStatus;
    public int biosphereSubsurfaceStatus;
    public boolean hasLife;
    public boolean hasSurfaceLife;

    // --- Атмосфера (доли) ---
    public double o2Pct;

    // --- Углеводороды (маркеры из БД) ---
    public boolean heavyHydrocarbons;
    public boolean lightHydrocarbons;

    // --- Орбита ---
    public double orbitalMeanMotionPerDay;
    public double orbitalPeriodDays;

    // --- Спутники и приливный форсинг ---
    public List<MoonTideSource> moonTideSources = new ArrayList<>();
    public double tidalDominantPeriodHours;
    public double tidalCyclesPerDay;
    public double tidalOpenOceanRangeM;

    // --- Подповерхностный океан под льдом (метры льда, 0 = нет) ---
    public int subsurfaceIceThicknessMeters;

    public PlanetConfig() {
    }

    public static class MoonTideSource {
        public int objectInternalId;
        public String objectName;
        public double massEarth;
        public double orbitSemimajorAxisAU;
        public double orbitInclinationDeg;
        public double meanMotionPerDay;
        public double forcingRelativeEarthMoon;
    }
}
