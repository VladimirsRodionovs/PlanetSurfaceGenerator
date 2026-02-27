package org.planet.core.model.config;

public class GeneratorSettings {

    public long seed;

    // Континенты
    public int continentBias;   // 0–100 (0 = 1 суперконтинент)
    public int islandBias;      // шанс островов
    public int archipelagoBias; // архипелаги

    // Общая доля океана
    public int oceanCoverage;   // 0–100

    // --- Erosion settings ---
    public int erosionIterations = 25;

    // Thermal (осыпание склонов)
    public double erosionThermalK = 0.35;

    // Water (водная)
    public double erosionWaterK = 0.020;
    public double erosionDepositionK = 0.60; // доля снятого, которую откладываем внизу (0..1)

    // Wind (ветровая)
    public double erosionWindK = 0.003;

    // Базовый "талус" (порог перепада высот для осыпания), в единицах elevation (1 = 100м)
    public double erosionTalusBase = 3.0;

    // Пороги для SurfaceType деградации
    public int hillMinElevation = 8;        // >= 800м
    public int mountainMinElevation = 20;   // >= 2000м

    // Climate moisture model:
    // ENHANCED = tuned gameplay model with extra heuristics,
    // PHYSICAL = reduced-heuristics transport/condensation.
    public ClimateModelMode climateModelMode = ClimateModelMode.ENHANCED;


    public GeneratorSettings(long seed) {
        this.seed = seed;
        String modeProp = System.getProperty("planet.climateMode");
        if (modeProp != null && !modeProp.isBlank()) {
            try {
                this.climateModelMode = ClimateModelMode.valueOf(modeProp.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                this.climateModelMode = ClimateModelMode.ENHANCED;
            }
        }
    }
}
