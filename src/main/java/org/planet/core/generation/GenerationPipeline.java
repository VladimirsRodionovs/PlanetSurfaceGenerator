package org.planet.core.generation;

import org.planet.core.generation.stages.*;
import org.planet.core.model.Tile;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;
import org.planet.core.generation.stages.ErosionStage;


import java.util.ArrayList;
import java.util.List;

public class GenerationPipeline {

    private final List<GenerationStage> stages = new ArrayList<>();
    private final StageProfile profile;
    private final boolean enableValidation;
    private final StageListener listener;

    /**
     * Полный конструктор.
     */
    public GenerationPipeline(double neighborDistanceThreshold,
                              double windAlpha,
                              double windBeta,
                              double windGamma,
                              StageProfile profile,
                              boolean enableValidation,
                              StageListener listener) {

        this.profile = profile;
        this.enableValidation = enableValidation;
        this.listener = (listener != null) ? listener : new ConsoleStageListener();

        // Фиксируем порядок стадий
        stages.add(new BuildNeighborsStage(neighborDistanceThreshold));
        stages.add(new BaseSurfaceStage());
        stages.add(new PlatesStage());
        stages.add(new StressStage());
        stages.add(new OrogenesisStage());
        stages.add(new MountainsStage());
        stages.add(new VolcanoStage());
        stages.add(new ClimateStage());
        stages.add(new WindStage(windAlpha, windBeta, windGamma));
        stages.add(new WaterRebalanceStage());
        stages.add(new ErosionStage());
        stages.add(new ClimateRecalcStage(windAlpha, windBeta, windGamma));
        stages.add(new SeasonalClimateStage(windAlpha, windBeta, windGamma));
        stages.add(new ImpactStage());
        stages.add(new IceStage());
        stages.add(new LavaStage());
        stages.add(new WaterClassifyStage());
        stages.add(new RiverStage(windAlpha, windBeta, windGamma));
        stages.add(new BiomeStage());
        stages.add(new ReliefStage());
        stages.add(new ResourceStage());

    }

    /**
     * Удобный конструктор по умолчанию:
     * - профиль до ветра
     * - валидации включены
     * - вывод в консоль
     */
    public GenerationPipeline(double neighborDistanceThreshold,
                              double windAlpha,
                              double windBeta,
                              double windGamma) {
        this(neighborDistanceThreshold, windAlpha, windBeta, windGamma,
                StageProfile.upToWind(), true, new ConsoleStageListener());
    }

    public void run(List<Tile> tiles, PlanetConfig planet, GeneratorSettings settings, int plateCount) {
        WorldContext ctx = new WorldContext(tiles, planet, settings, plateCount);

        for (GenerationStage stage : stages) {
            if (!profile.isEnabled(stage.id())) {
                System.out.println("[STAGE SKIP]  " + stage.id() + " - " + stage.name());
                continue;
            }

            long start = System.currentTimeMillis();
            listener.onStageStart(stage.id(), stage.name());

            try {
                stage.apply(ctx);

                if (enableValidation) {
                    runValidation(stage.id(), ctx);
                }

            } catch (RuntimeException e) {
                throw new RuntimeException("Generation failed at stage: " + stage.id() + " - " + stage.name(), e);
            } finally {
                long elapsed = System.currentTimeMillis() - start;
                listener.onStageEnd(stage.id(), stage.name(), elapsed);
            }
        }

        WorldStats stats = WorldStats.compute(ctx.tiles);
        WorldStatsReport.print(stats);
    }

    private void runValidation(StageId id, WorldContext ctx) {
        switch (id) {
            case NEIGHBORS -> Validation.afterNeighbors(ctx);
            case BASE_SURFACE -> Validation.afterBaseSurface(ctx);
            case PLATES -> Validation.afterPlates(ctx);
            case STRESS -> Validation.afterStress(ctx);
            case OROGENESIS -> Validation.afterOrogenesis(ctx);
            case MOUNTAINS -> Validation.afterMountains(ctx);
            case VOLCANISM -> Validation.afterVolcanism(ctx);
            case CLIMATE -> Validation.afterClimate(ctx);
            case WIND -> Validation.afterWind(ctx);
            case WATER_REBALANCE -> {
                // no validation yet
            }
            case WATER_CLASSIFY -> {
                // no validation yet
            }
            case EROSION -> Validation.afterErosion(ctx);
            case CLIMATE_RECALC -> {
                // no validation yet
            }
            case IMPACTS -> {
                // no validation yet
            }
            case ICE -> {
                // no validation yet
            }
            case LAVA -> {
                // no validation yet
            }
            case RIVERS -> {
                // no validation yet
            }
            case RELIEF -> {
                // no validation yet
            }
            case BIOMES -> {
                // no validation yet
            }
            case RESOURCES -> {
                // no validation yet
            }
            default -> {
                // будущие стадии пока без проверок
            }
        }
    }
}
