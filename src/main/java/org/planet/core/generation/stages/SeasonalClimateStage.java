package org.planet.core.generation.stages;

import org.planet.core.generation.ClimateGenerator;
import org.planet.core.generation.ClimateSampler;
import org.planet.core.generation.GenerationStage;
import org.planet.core.generation.StageId;
import org.planet.core.generation.WorldContext;
import org.planet.core.generation.WindGenerator;
import org.planet.core.model.Tile;

import java.util.List;

public class SeasonalClimateStage implements GenerationStage {

    private final double alpha;
    private final double beta;
    private final double gamma;

    public SeasonalClimateStage(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    @Override
    public StageId id() {
        return StageId.SEASONAL_CLIMATE;
    }

    @Override
    public String name() {
        return "Seasonal Climate";
    }

    @Override
    public void apply(WorldContext ctx) {
        List<Tile> tiles = ctx.tiles;

        // save annual state
        double[] temp = new double[tiles.size()];
        int[] pressure = new int[tiles.size()];
        double[] windX = new double[tiles.size()];
        double[] windY = new double[tiles.size()];
        double[] moisture = new double[tiles.size()];
        double[] atmMoist = new double[tiles.size()];
        double[] tempMin = new double[tiles.size()];
        double[] tempMax = new double[tiles.size()];
        double[] windAvg = new double[tiles.size()];
        double[] windMax = new double[tiles.size()];
        double[] precipAvg = new double[tiles.size()];
        double[] evapAvg = new double[tiles.size()];
        double[] precipKgM2Day = new double[tiles.size()];
        double[] evapKgM2Day = new double[tiles.size()];
        double[] runoffKgM2Day = new double[tiles.size()];
        int[] sunnyDays = new int[tiles.size()];

        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            temp[i] = t.temperature;
            pressure[i] = t.pressure;
            windX[i] = t.windX;
            windY[i] = t.windY;
            moisture[i] = t.moisture;
            atmMoist[i] = t.atmMoist;
            tempMin[i] = t.tempMin;
            tempMax[i] = t.tempMax;
            windAvg[i] = t.windAvg;
            windMax[i] = t.windMax;
            precipAvg[i] = t.precipAvg;
            evapAvg[i] = t.evapAvg;
            precipKgM2Day[i] = t.precipKgM2Day;
            evapKgM2Day[i] = t.evapKgM2Day;
            runoffKgM2Day[i] = t.surfaceRunoffKgM2Day;
            sunnyDays[i] = t.sunnyDays;
            t.biomeTempInterseason = t.temperature;
            t.biomePrecipInterseason = t.precipAvg;
            t.biomeEvapInterseason = t.evapAvg;
            t.biomeMoistureInterseason = t.moisture;
            t.tempMinInterseason = Double.isNaN(t.tempMin) ? t.temperature : t.tempMin;
            t.tempMaxInterseason = Double.isNaN(t.tempMax) ? t.temperature : t.tempMax;
            t.precipKgM2DayInterseason = t.precipKgM2Day;
            t.evapKgM2DayInterseason = t.evapKgM2Day;
            t.surfaceRunoffKgM2DayInterseason = t.surfaceRunoffKgM2Day;
        }

        ClimateGenerator climate = new ClimateGenerator();
        WindGenerator wind = new WindGenerator(alpha, beta, gamma);

        double tilt = ctx.planet.axialTilt;
        if (Double.isNaN(tilt)) tilt = 0.0;
        if (tilt > 90.0) tilt = 90.0;
        if (tilt < -90.0) tilt = -90.0;

        // Season A (+tilt)
        clearSeasonTempRange(tiles);
        climate.generateSeason(tiles, ctx.planet, tilt);
        wind.generateWind(tiles, ctx.planet, tilt, ctx.settings.seed, ctx.settings.climateModelMode);
        ClimateSampler.sample(tiles, ctx.planet);
        SeasonSnapshot seasonA = SeasonSnapshot.capture(tiles);

        // Season B must start from annual baseline, not from season-A mutated moisture/wind state.
        restoreAnnualState(tiles, temp, pressure, windX, windY, moisture, atmMoist, tempMin, tempMax, windAvg, windMax, precipAvg, evapAvg, precipKgM2Day, evapKgM2Day, runoffKgM2Day, sunnyDays);

        // Season B (-tilt)
        clearSeasonTempRange(tiles);
        climate.generateSeason(tiles, ctx.planet, -tilt);
        wind.generateWind(tiles, ctx.planet, -tilt, ctx.settings.seed, ctx.settings.climateModelMode);
        ClimateSampler.sample(tiles, ctx.planet);
        SeasonSnapshot seasonB = SeasonSnapshot.capture(tiles);

        // Global seasonal snapshots for UI/dumps:
        // +tilt is stored as "warm", -tilt as "cold" (planetary-season view).
        applyGlobalSeasonView(tiles, seasonA, seasonB);
        // Per-tile warm/cold mapping for biome logic:
        // whichever seasonal run is warmer for a tile is treated as local summer for that tile.
        applyLocalWarmColdForBiomes(tiles, seasonA, seasonB);

        // restore annual state
        restoreAnnualState(tiles, temp, pressure, windX, windY, moisture, atmMoist, tempMin, tempMax, windAvg, windMax, precipAvg, evapAvg, precipKgM2Day, evapKgM2Day, runoffKgM2Day, sunnyDays);
    }

    private static void applyGlobalSeasonView(List<Tile> tiles, SeasonSnapshot warm, SeasonSnapshot cold) {
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            t.tempWarm = warm.temp[i];
            t.windWarm = warm.windAvg[i];
            t.windMaxWarm = warm.windMax[i];
            t.precipWarm = warm.precip[i];
            t.evapWarm = warm.evap[i];
            t.sunnyWarm = warm.sunnyDays[i];
            t.moistureWarm = warm.moisture[i];
            t.windXWarm = warm.windX[i];
            t.windYWarm = warm.windY[i];
            t.tempMinWarm = warm.tempMin[i];
            t.tempMaxWarm = warm.tempMax[i];
            t.precipKgM2DayWarm = warm.precipKgM2Day[i];
            t.evapKgM2DayWarm = warm.evapKgM2Day[i];
            t.surfaceRunoffKgM2DayWarm = warm.runoffKgM2Day[i];

            t.tempCold = cold.temp[i];
            t.windCold = cold.windAvg[i];
            t.windMaxCold = cold.windMax[i];
            t.precipCold = cold.precip[i];
            t.evapCold = cold.evap[i];
            t.sunnyCold = cold.sunnyDays[i];
            t.moistureCold = cold.moisture[i];
            t.windXCold = cold.windX[i];
            t.windYCold = cold.windY[i];
            t.tempMinCold = cold.tempMin[i];
            t.tempMaxCold = cold.tempMax[i];
            t.precipKgM2DayCold = cold.precipKgM2Day[i];
            t.evapKgM2DayCold = cold.evapKgM2Day[i];
            t.surfaceRunoffKgM2DayCold = cold.runoffKgM2Day[i];
        }
    }

    private static void applyLocalWarmColdForBiomes(List<Tile> tiles, SeasonSnapshot a, SeasonSnapshot b) {
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            boolean aIsWarm = a.temp[i] >= b.temp[i];
            SeasonSnapshot warm = aIsWarm ? a : b;
            SeasonSnapshot cold = aIsWarm ? b : a;

            t.biomeTempWarm = warm.temp[i];
            t.biomePrecipWarm = warm.precip[i];
            t.biomeEvapWarm = warm.evap[i];
            t.biomeMoistureWarm = warm.moisture[i];
            t.biomeWarmFromPositiveTilt = aIsWarm ? 1 : 0;

            t.biomeTempCold = cold.temp[i];
            t.biomePrecipCold = cold.precip[i];
            t.biomeEvapCold = cold.evap[i];
            t.biomeMoistureCold = cold.moisture[i];
        }
    }

    private static void clearSeasonTempRange(List<Tile> tiles) {
        for (Tile t : tiles) {
            t.tempMin = Double.NaN;
            t.tempMax = Double.NaN;
        }
    }

    private static final class SeasonSnapshot {
        final double[] temp;
        final double[] windAvg;
        final double[] windMax;
        final double[] tempMin;
        final double[] tempMax;
        final double[] precip;
        final double[] evap;
        final double[] precipKgM2Day;
        final double[] evapKgM2Day;
        final double[] runoffKgM2Day;
        final int[] sunnyDays;
        final double[] moisture;
        final double[] windX;
        final double[] windY;

        private SeasonSnapshot(int size) {
            this.temp = new double[size];
            this.windAvg = new double[size];
            this.windMax = new double[size];
            this.tempMin = new double[size];
            this.tempMax = new double[size];
            this.precip = new double[size];
            this.evap = new double[size];
            this.precipKgM2Day = new double[size];
            this.evapKgM2Day = new double[size];
            this.runoffKgM2Day = new double[size];
            this.sunnyDays = new int[size];
            this.moisture = new double[size];
            this.windX = new double[size];
            this.windY = new double[size];
        }

        static SeasonSnapshot capture(List<Tile> tiles) {
            SeasonSnapshot s = new SeasonSnapshot(tiles.size());
            for (int i = 0; i < tiles.size(); i++) {
                Tile t = tiles.get(i);
                s.temp[i] = t.temperature;
                s.windAvg[i] = t.windAvg;
                s.windMax[i] = t.windMax;
                s.tempMin[i] = Double.isNaN(t.tempMin) ? t.temperature : t.tempMin;
                s.tempMax[i] = Double.isNaN(t.tempMax) ? t.temperature : t.tempMax;
                s.precip[i] = t.precipAvg;
                s.evap[i] = t.evapAvg;
                s.precipKgM2Day[i] = t.precipKgM2Day;
                s.evapKgM2Day[i] = t.evapKgM2Day;
                s.runoffKgM2Day[i] = t.surfaceRunoffKgM2Day;
                s.sunnyDays[i] = t.sunnyDays;
                s.moisture[i] = t.moisture;
                s.windX[i] = t.windX;
                s.windY[i] = t.windY;
            }
            return s;
        }
    }

    private static void restoreAnnualState(
            List<Tile> tiles,
            double[] temp,
            int[] pressure,
            double[] windX,
            double[] windY,
            double[] moisture,
            double[] atmMoist,
            double[] tempMin,
            double[] tempMax,
            double[] windAvg,
            double[] windMax,
            double[] precipAvg,
            double[] evapAvg,
            double[] precipKgM2Day,
            double[] evapKgM2Day,
            double[] runoffKgM2Day,
            int[] sunnyDays
    ) {
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            t.temperature = (int) Math.round(temp[i]);
            t.pressure = pressure[i];
            t.windX = windX[i];
            t.windY = windY[i];
            t.moisture = moisture[i];
            t.atmMoist = atmMoist[i];
            t.tempMin = tempMin[i];
            t.tempMax = tempMax[i];
            t.windAvg = windAvg[i];
            t.windMax = windMax[i];
            t.precipAvg = precipAvg[i];
            t.evapAvg = evapAvg[i];
            t.precipKgM2Day = precipKgM2Day[i];
            t.evapKgM2Day = evapKgM2Day[i];
            t.surfaceRunoffKgM2Day = runoffKgM2Day[i];
            t.sunnyDays = sunnyDays[i];
        }
    }
}
