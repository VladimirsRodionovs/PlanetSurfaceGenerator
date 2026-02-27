package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.ClimateModelMode;
import org.planet.core.model.config.PlanetConfig;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

public class WindGenerator {

    private final double alpha; // forcing pressure-gradient
    private final double beta;  // forcing thermal-gradient
    private final double gamma; // thermal advection coupling

    private double hadleyEdge = 30.0;
    private double ferrelEdge = 60.0;
    private double itczShift = 0.0;

    private static final int WIND_RELAX_ITERS = 28;
    private static final int TEMP_ADVECT_ITERS = 6;

    // Calibration from solver units to climate-layer m/s (not surface wind resource layer).
    private static final double WIND_UNIT_TO_MPS = 0.50;

    private static final double MAX_WIND = 55.0;
    private static final double TILE_DIST_M = 100_000.0;
    private static final int DEFAULT_CLIMATE_STEP_HOURS = 6;

    private static final int TEMP_MIN = -220;
    private static final int TEMP_MAX = 900;

    public WindGenerator(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    private static void forEachIndex(int n, IntConsumer action) {
        if (climateParallelEnabled()) {
            IntStream.range(0, n).parallel().forEach(action);
        } else {
            for (int i = 0; i < n; i++) action.accept(i);
        }
    }

    private static boolean climateParallelEnabled() {
        return Boolean.parseBoolean(System.getProperty("planet.climate.parallel", "true"));
    }

    public void generateWind(List<Tile> tiles, PlanetConfig planet) {
        generateWind(tiles, planet, 0.0, 0L, ClimateModelMode.ENHANCED);
    }

    public void generateWind(List<Tile> tiles, PlanetConfig planet, double seasonalShiftLat) {
        generateWind(tiles, planet, seasonalShiftLat, 0L, ClimateModelMode.ENHANCED);
    }

    public void generateWind(List<Tile> tiles, PlanetConfig planet, long baseSeed) {
        generateWind(tiles, planet, 0.0, baseSeed, ClimateModelMode.ENHANCED);
    }

    public void generateWind(List<Tile> tiles, PlanetConfig planet, double seasonalShiftLat, long baseSeed) {
        generateWind(tiles, planet, seasonalShiftLat, baseSeed, ClimateModelMode.ENHANCED);
    }

    public void generateWind(List<Tile> tiles,
                             PlanetConfig planet,
                             double seasonalShiftLat,
                             long baseSeed,
                             ClimateModelMode mode) {
        ClimateModelMode modelMode = (mode == null) ? ClimateModelMode.ENHANCED : mode;
        int prograde = (planet.rotationPrograde == 0) ? -1 : 1;
        long windSeed = windSeed(planet, baseSeed);
        double atm = clamp(planet.atmosphereDensity, 0.05, 5.0);
        double atmBaseScale = clamp(0.78 + 0.28 * Math.sqrt(atm), 0.60, 1.35);

        double rotHours = planet.rotationPeriodHours > 0.0 ? planet.rotationPeriodHours : 24.0;
        double rotFactor = clamp(24.0 / rotHours, 0.25, 4.0);
        if (planet.rotationSpeed > 0.0) {
            rotFactor *= clamp(planet.rotationSpeed, 0.5, 3.0);
        }
        double zonalBias = clamp(0.75 + 0.25 * rotFactor, 0.6, 1.7);
        double meridDamp = clamp(1.0 / (1.0 + 0.65 * rotFactor), 0.30, 1.0);
        double angularSpeed = planet.tidalLocked ? 0.0 : 2.0 * Math.PI / (rotHours * 3600.0);

        double tiltAbs = Math.abs(planet.axialTilt);
        this.itczShift = clamp(seasonalShiftLat, -90.0, 90.0);
        this.hadleyEdge = 30.0 + Math.min(10.0, tiltAbs * 0.2);
        this.ferrelEdge = 60.0 + Math.min(8.0, tiltAbs * 0.15);

        // 1) Initial physically-guided field (zonal cells + tidal scenario).
        for (Tile t : tiles) {
            double[] v = planet.tidalLocked
                    ? tidalBaseWind(t, rotFactor, atmBaseScale)
                    : beltBaseWind(t, rotFactor, zonalBias, meridDamp, prograde, windSeed, atmBaseScale);

            // First-order forcing from local pressure/thermal gradients.
            double[] gradP = pressureGradientVector(t);
            double[] gradT = thermalGradientVector(t);
            v[0] += gradP[0] * alpha * 3.0 + gradT[0] * beta * 1.2;
            v[1] += gradP[1] * alpha * 3.0 + gradT[1] * beta * 1.2;

            // Orographic damping before iterative relaxation.
            double slope = maxSlope(t);
            double relief = 1.0 / (1.0 + 0.04 * slope + 0.006 * Math.max(0, t.elevation));
            v[0] *= relief;
            v[1] *= relief;

            double[] clamped = limitVector(v[0], v[1], MAX_WIND);
            t.windX = clamped[0];
            t.windY = clamped[1];
        }

        // 2) Relaxation: pressure + Coriolis + terrain channeling + neighbor coupling.
        relaxWindField(tiles, planet, prograde, rotFactor, angularSpeed, windSeed);

        // 3) Moisture init + heat transport + moisture cycle.
        initializeMoistureIfNeeded(tiles, planet);
        advectTemperature(tiles);
        simulateMoistureCycle(tiles, planet, modelMode);

        // 4) Final output in physical display units (m/s).
        convertWindsToMetersPerSecond(tiles);
    }

    private double[] beltBaseWind(Tile t,
                                  double rotFactor,
                                  double zonalBias,
                                  double meridDamp,
                                  int prograde,
                                  long windSeed,
                                  double atmBaseScale) {
        double latGeo = t.lat;
        double latEff = t.lat - itczShift;
        double latGeoAbs = Math.abs(latGeo);
        double latAbs = Math.abs(latEff);
        double seasonHemi = seasonalHemisphereIndex(t.lat);
        double seasonStrength = seasonalInfluenceStrength(t.lat);
        double seasonBlend = seasonalBeltBlendStrength();

        double base = 11.0 * rotFactor;
        double jet = gaussian(latAbs, ferrelEdge, 6.0);
        double doldrumWidth = 7.0;
        double doldrum = 1.0 - clamp((doldrumWidth - Math.abs(latEff)) / doldrumWidth, 0.0, 1.0);

        double[] seasonComp = beltComponents(latEff, prograde);
        double[] neutralComp = beltComponents(latGeo, prograde);
        // Anchor seasonal runs to equinox-like belt structure.
        double zonal = neutralComp[0] * (1.0 - seasonBlend) + seasonComp[0] * seasonBlend;
        double merid = neutralComp[1] * (1.0 - seasonBlend) + seasonComp[1] * seasonBlend;

        // Break strict latitudinal linearity with deterministic turbulence.
        double n = noise01(windSeed, t.id);
        double jitter = (n * 2.0 - 1.0);
        double jitterScale = 1.0 - 0.45 * seasonBlend;
        zonal += jitter * 0.12 * jitterScale;
        merid += jitter * 0.10 * jitterScale;

        // Equatorial ad-hoc boost disabled: keep latitude belts without extra tropical amplification.
        // Equatorial/tropical trade flow is predominantly zonal in this layer.
        // Use geographic latitude (not ITCZ-shifted) to keep symmetric +/- bands.
        double tropicalBand = smoothStep(tropicalCoreLat(), tropicalFadeLat(), latGeoAbs);
        double tropicalMeridSupp = 0.28 + 0.72 * tropicalBand;
        merid *= tropicalMeridSupp;
        // Outside tropics, real large-scale flow becomes mostly zonal.
        double meridSupp = 1.0 - 0.80 * smoothStep(midLatZonalStart(), midLatZonalEnd(), latAbs);
        merid *= meridSupp;
        // Stronger westerly/easterly channels in mid/high latitudes.
        double zonalBoost = 1.0
                + 0.50 * gaussian(latAbs, 48.0, 13.0)
                + 0.18 * gaussian(latAbs, 64.0, 10.0);
        // Seasonal hemisphere asymmetry:
        // winter hemisphere (seasonHemi<0) -> stronger baroclinicity/jet,
        // summer hemisphere (seasonHemi>0) -> slightly weaker.
        double midLatBand = gaussian(latAbs, 47.0, 14.0);
        double polarFrontBand = gaussian(latAbs, 60.0, 11.0);
        double beltBand = clamp(0.70 * midLatBand + 0.50 * polarFrontBand, 0.0, 1.0);
        double winterBoost = 1.0 + 0.16 * seasonStrength * beltBand * clamp(-seasonHemi, 0.0, 1.0);
        double summerDamp = 1.0 - 0.08 * seasonStrength * beltBand * clamp(seasonHemi, 0.0, 1.0);
        zonalBoost *= winterBoost * summerDamp;
        zonal *= zonalBoost;

        // Meridional branch shifts with seasons:
        // stronger poleward branch in summer hemisphere, weaker in winter hemisphere.
        double hadleyBand = smoothStep(6.0, Math.max(8.0, hadleyEdge * 0.85), latAbs)
                * (1.0 - smoothStep(hadleyEdge * 1.05, hadleyEdge + 10.0, latAbs));
        double meridSeason = 1.0
                + 0.08 * seasonStrength * hadleyBand * clamp(seasonHemi, 0.0, 1.0)
                - 0.06 * seasonStrength * hadleyBand * clamp(-seasonHemi, 0.0, 1.0);
        merid *= clamp(meridSeason, 0.78, 1.28);

        double vx = zonal * base * zonalBias * doldrum * (1.0 + 0.95 * jet) * atmBaseScale;
        double vy = merid * base * meridDamp * (1.0 - 0.22 * jet) * atmBaseScale;

        return new double[]{vx, vy};
    }

    private double[] beltComponents(double lat, int prograde) {
        double latAbs = Math.abs(lat);
        double zonal;
        double merid;
        if (latAbs < hadleyEdge) {
            zonal = -prograde * (0.60 + 0.40 * (latAbs / hadleyEdge));
            merid = -Math.signum(lat) * 0.58;
        } else if (latAbs < ferrelEdge) {
            zonal = prograde * 0.82;
            merid = Math.signum(lat) * 0.42;
        } else {
            zonal = -prograde * 0.68;
            merid = -Math.signum(lat) * 0.46;
        }
        return new double[]{zonal, merid};
    }

    private double[] tidalBaseWind(Tile t, double rotFactor, double atmBaseScale) {
        // Net transport from nightside to dayside with terminator acceleration.
        double[] toDay = directionTo(t.lat, t.lon, itczShift, 0.0);
        double ang = angularDistanceDeg(t.lat, t.lon, itczShift, 0.0);
        double term = Math.sin(Math.toRadians(ang));
        double base = (4.0 + 9.0 * term) * rotFactor * atmBaseScale;
        return new double[]{toDay[0] * base, toDay[1] * base};
    }

    private void relaxWindField(List<Tile> tiles,
                                PlanetConfig planet,
                                int prograde,
                                double rotFactor,
                                double angularSpeed,
                                long seed) {
        int n = tiles.size();
        double[] nextX = new double[n];
        double[] nextY = new double[n];
        double atm = clamp(planet.atmosphereDensity, 0.05, 5.0);
        double atmForceScale = clamp(0.74 + 0.36 * Math.sqrt(atm), 0.55, 1.55);
        double atmThermalScale = clamp(1.12 / Math.sqrt(atm), 0.55, 2.25);
        double atmDragScale = clamp(0.72 + 0.36 * atm, 0.55, 2.35);
        double atmTurbScale = clamp(1.18 / Math.sqrt(atm), 0.55, 2.60);

        double smooth = planet.tidalLocked ? 0.36 : 0.33;
        double forceP = 0.026 * (0.55 + rotFactor * 0.40) * atmForceScale;
        double forceT = 0.022 * atmThermalScale;
        double channelK = 0.13;

        for (int iter = 0; iter < WIND_RELAX_ITERS; iter++) {
            final int iterF = iter;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                double vx = t.windX;
                double vy = t.windY;

                if (t.neighbors == null || t.neighbors.isEmpty()) {
                    nextX[i] = vx;
                    nextY[i] = vy;
                    return;
                }

                double avgX = 0.0;
                double avgY = 0.0;
                for (Tile nb : t.neighbors) {
                    avgX += nb.windX;
                    avgY += nb.windY;
                }
                avgX /= t.neighbors.size();
                avgY /= t.neighbors.size();

                double[] gradP = pressureGradientVector(t);
                double[] gradT = thermalGradientVector(t);
                double[] steer = terrainSteeringVector(t, vx, vy);

                double cx = 0.0;
                double cy = 0.0;
                if (!planet.tidalLocked) {
                    // Coriolis acceleration (scaled for stability on coarse grid).
                    double f = 2.0 * angularSpeed * Math.sin(Math.toRadians(t.lat)) * prograde;
                    double cScale = 220.0;
                    cx = -f * vy * cScale;
                    cy = f * vx * cScale;
                }

                double rough = surfaceDrag(t.surfaceType);
                double slope = maxSlope(t);
                double reliefDrag = clamp((rough + 0.008 * slope + 0.0008 * Math.max(0, t.elevation)) * atmDragScale, 0.05, 0.58);

                double turbA = (noise01(seed + 7919L * (iterF + 1), t.id) * 2.0 - 1.0);
                double turbB = (noise01(seed + 104729L * (iterF + 3), t.id) * 2.0 - 1.0);
                double turbScale = (0.22 + 0.015 * slope) * atmTurbScale;

                double tx = vx * (1.0 - reliefDrag)
                        + avgX * smooth
                        + gradP[0] * forceP
                        + gradT[0] * forceT
                        + steer[0] * channelK
                        + cx
                        + turbA * turbScale;

                double ty = vy * (1.0 - reliefDrag)
                        + avgY * smooth
                        + gradP[1] * forceP
                        + gradT[1] * forceT
                        + steer[1] * channelK
                        + cy
                        + turbB * turbScale;

                // Keep static mountains from becoming artificial jets.
                double barrier = 1.0 / (1.0 + 0.010 * Math.max(0, t.elevation));
                tx *= barrier;
                ty *= barrier;

                // Quadratic drag suppresses runaway acceleration and reduces global clipping.
                double magPre = Math.sqrt(tx * tx + ty * ty);
                double qDrag = 0.010 + 0.0008 * slope;
                tx /= (1.0 + qDrag * magPre);
                ty /= (1.0 + qDrag * magPre);

                double[] limited = limitVector(tx, ty, MAX_WIND);
                nextX[i] = limited[0];
                nextY[i] = limited[1];
            });

            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                t.windX = nextX[i];
                t.windY = nextY[i];
            });
        }
    }

    private void initializeMoistureIfNeeded(List<Tile> tiles, PlanetConfig planet) {
        double landRhBase = switch (planet.waterCoverageOrdinal) {
            case 0 -> 0.06;
            case 1 -> 0.10;
            case 2 -> 0.16;
            case 3 -> 0.24;
            default -> 0.32;
        };
        if (!planet.hasLife) {
            landRhBase *= 0.85;
        }
        final double landRhBaseFinal = landRhBase;

        forEachIndex(tiles.size(), i -> {
            Tile t = tiles.get(i);
            if (Double.isNaN(t.moisture)) {
                switch (t.surfaceType) {
                    case OCEAN, OPEN_WATER_SHALLOW, OPEN_WATER_DEEP, STEAM_SEA -> t.moisture = 95.0;
                    case LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID -> t.moisture = 82.0;
                    case SWAMP, MUD_SWAMP, BASIN_SWAMP -> t.moisture = 88.0;
                    case LAVA_OCEAN -> t.moisture = 0.0;
                    case ICE, GLACIER -> t.moisture = 20.0;
                    default -> t.moisture = 6.0;
                }
            }
            if (Double.isNaN(t.atmMoist)) {
                double rh;
                if (isWaterSurface(t.surfaceType)) {
                    rh = 0.86;
                } else if (t.surfaceType == SurfaceType.ICE || t.surfaceType == SurfaceType.GLACIER) {
                    rh = 0.24;
                } else {
                    double soil = Double.isNaN(t.moisture) ? 0.0 : t.moisture;
                    double soilFactor = clamp(0.30 + 0.70 * (soil / 100.0), 0.30, 1.00);
                    rh = landRhBaseFinal * soilFactor;
                    if (planet.waterCoverageOrdinal == 0 && t.temperature > 70.0) {
                        rh *= 0.45;
                    }
                    if (!hasWaterNeighbor(t)) {
                        rh *= 0.80;
                    }
                }
                double qsat = saturationSpecificHumidity(t);
                t.atmMoist = clamp(qsat * rh, 0.0, qsat);
            }
        });
    }

    private void advectTemperature(List<Tile> tiles) {
        int n = tiles.size();
        double[] temp = new double[n];
        final double[] tempInit = temp;
        forEachIndex(n, i -> {
            tempInit[i] = tiles.get(i).temperature;
        });

        for (int iter = 0; iter < TEMP_ADVECT_ITERS; iter++) {
            double[] delta = new double[n];
            final double[] tempNow = temp;

            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                if (t.neighbors == null || t.neighbors.isEmpty()) return;

                double speedInternal = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
                if (speedInternal < 1e-6) return;
                double speedMps = speedInternal * WIND_UNIT_TO_MPS;

                double adv = clamp((speedMps * 1800.0) / TILE_DIST_M, 0.0, 0.36);
                if (adv <= 1e-6) return;

                double wx = t.windX / (speedInternal + 1e-9);
                double wy = t.windY / (speedInternal + 1e-9);

                double inTemp = 0.0;
                double wSum = 0.0;
                for (Tile nb : t.neighbors) {
                    double[] dir = direction(nb, t);
                    double dot = wx * dir[0] + wy * dir[1];
                    if (dot <= 0.02) continue;
                    double w = dot;
                    inTemp += tempNow[nb.id] * w;
                    wSum += w;
                }
                if (wSum <= 1e-9) return;

                double upwindTemp = inTemp / wSum;
                double dT = (upwindTemp - tempNow[i]) * adv * gamma;
                delta[i] += clamp(dT, -6.0, 6.0);
            });

            forEachIndex(n, i -> {
                tempNow[i] = clamp(tempNow[i] + delta[i], TEMP_MIN, TEMP_MAX);
            });
            temp = frontEddyMixConservative(tiles, temp, 0.018, false);
        }

        final double[] tempFinal = temp;
        forEachIndex(n, i -> {
            tiles.get(i).temperature = (int) Math.round(tempFinal[i]);
        });
    }

    private void simulateMoistureCycle(List<Tile> tiles, PlanetConfig planet, ClimateModelMode mode) {
        final int n = tiles.size();
        final double g = 9.81;
        final int stepHours = climateStepHours();
        final double stepScale = stepHours;
        final double dt = 3600.0 * stepHours;
        final double soilPerMm = 1.8;
        final boolean aridWorld = planet.waterCoverageOrdinal == 0;
        final double atm = clamp(planet.atmosphereDensity, 0.05, 8.0);
        final double atmEvapScale = clamp(0.80 + 0.25 * Math.sqrt(atm), 0.55, 1.90);
        final boolean enhancedMode = mode == ClimateModelMode.ENHANCED;

        double[] iwv = new double[n];
        double[] soil = new double[n];
        double[] soilStart = new double[n];
        double[] soilFromPrecip = new double[n];
        double[] soilFromEvap = new double[n];
        double[] soilFromDiff = new double[n];
        double[] precipTotal = new double[n];
        double[] evapTotal = new double[n];
        double[] runoffTotal = new double[n];
        double[] tempDiagMin = new double[n];
        double[] tempDiagMax = new double[n];
        double[] tempDiagSum = new double[n];
        int[] tempDiagCnt = new int[n];

        final double[] soilInit = soil;
        final double[] iwvInit = iwv;
        forEachIndex(n, i -> {
            Tile t = tiles.get(i);
            soilInit[i] = Double.isNaN(t.moisture) ? 40.0 : t.moisture;
            soilStart[i] = soilInit[i];
            iwvInit[i] = iwvFromAtmMoist(t, g);
            tempDiagMin[i] = Double.POSITIVE_INFINITY;
            tempDiagMax[i] = Double.NEGATIVE_INFINITY;
        });

        int hoursPerDay = 24;
        int stepsPerDay = Math.max(1, hoursPerDay / stepHours);
        int spinupDays = 20;
        int iters = stepsPerDay * (spinupDays + 1);
        int sampleStartIter = iters - stepsPerDay;
        int advSubSteps = Math.max(1, (int) Math.round(stepHours / 1.0));
        for (int iter = 0; iter < iters; iter++) {
            double[] tempPhase = new double[n];
            final int iterF = iter;
            final double[] soilNowForTemp = soil;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                double tp = diurnalPhaseTemperatureC(t, soilNowForTemp[i], iterF, stepsPerDay, stepHours, atm);
                tempPhase[i] = tp;
                if (iterF >= sampleStartIter) {
                    tempDiagMin[i] = Math.min(tempDiagMin[i], tp);
                    tempDiagMax[i] = Math.max(tempDiagMax[i], tp);
                    tempDiagSum[i] += tp;
                    tempDiagCnt[i]++;
                }
            });

            // 1) Evaporation source.
            final double[] iwvNowEvap = iwv;
            final double[] soilNowEvap = soil;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                boolean water = isWaterSurface(t.surfaceType);
                boolean wetland = isWetlandSurface(t.surfaceType);
                double windMag = Math.sqrt(t.windX * t.windX + t.windY * t.windY) * WIND_UNIT_TO_MPS;
                double dpPa = moistureLayerPressurePa(t);
                if (dpPa <= 1e-6) return;

                double qsat = saturationSpecificHumidity(tempPhase[i], t.pressure);
                double q = (iwvNowEvap[i] * g / dpPa) * 1000.0;
                double deficit = Math.max(0.0, qsat - q);
                double deficitN = clamp((qsat > 1e-6) ? (deficit / qsat) : 0.0, 0.0, 1.0);

                double temp = tempPhase[i];
                double tempFactor = clamp((temp + 25.0) / 70.0, 0.0, 2.2);
                double windFactor = clamp(0.45 + windMag / 14.0, 0.35, 2.2);
                double evapPot = deficitN * tempFactor * windFactor * atmEvapScale * 2.2;
                if (water) {
                    if (t.surfaceType == SurfaceType.SEA_ICE_SHALLOW || t.surfaceType == SurfaceType.SEA_ICE_DEEP || t.surfaceType == SurfaceType.ICE_OCEAN) {
                        evapPot *= 0.14;
                    } else if (t.surfaceType == SurfaceType.STEAM_SEA) {
                        evapPot *= 1.30;
                    } else {
                        evapPot *= 1.45;
                    }
                } else if (enhancedMode) {
                    if (wetland) {
                        evapPot *= 1.18;
                    } else if (isForestSurface(t.surfaceType)) {
                        evapPot *= 1.12;
                    }
                }
                if (!water) {
                    double soilFrac = clamp(soilNowEvap[i] / 100.0, 0.0, 1.0);
                    double soilAvailFactor = wetland
                            ? (0.60 + 0.40 * soilFrac)
                            : (0.20 + 0.80 * soilFrac);
                    evapPot *= soilAvailFactor;
                    if (aridWorld) {
                        evapPot *= 0.22;
                    }
                }

                double avail = water
                        ? 1.0
                        : (wetland
                        ? clamp(0.60 + soilNowEvap[i] / 250.0, 0.60, 1.0)
                        : clamp(soilNowEvap[i] / 100.0, 0.0, 1.0));
                double eCap = 4.2 * stepScale;
                if (!water) {
                    // Land cannot evaporate more than local soil reservoir allows during this step.
                    double reservoirMm = Math.max(0.0, soilNowEvap[i]) / soilPerMm;
                    double reservoirTake = wetland ? 0.98 : 0.92;
                    eCap = Math.min(eCap, reservoirMm * reservoirTake);
                }
                double eAct = clamp(evapPot * avail * stepScale, 0.0, eCap);
                iwvNowEvap[i] += eAct;
                if (iterF >= sampleStartIter) {
                    evapTotal[i] += eAct;
                }
                if (!water) {
                    soilNowEvap[i] = clamp(soilNowEvap[i] - eAct * soilPerMm, 0.0, 100.0);
                    if (iterF >= sampleStartIter) {
                        soilFromEvap[i] += eAct * soilPerMm;
                    }
                }
            });

            // 2) Conservative advection in two sub-steps to reduce directional artifacts.
            double[] iwvBeforeAdvection = iwv.clone();
            double dtSub = dt / advSubSteps;
            for (int sub = 0; sub < advSubSteps; sub++) {
                    iwv = advectIwvConservative(tiles, iwv, dtSub, mode);
                }
            double[] convRaw = new double[n];
            final double[] iwvNowConv = iwv;
            forEachIndex(n, i -> convRaw[i] = iwvNowConv[i] - iwvBeforeAdvection[i]);
            double[] conv = enhancedMode
                    ? smoothScalar(tiles, smoothScalar(tiles, smoothScalar(tiles, convRaw, 0.52), 0.52), 0.52)
                    : smoothScalar(tiles, convRaw, 0.20);

            // 3) Condensation / precipitation sink.
            double[] precipPot = new double[n];
            double[] precipCap = new double[n];
            final double[] iwvNowPrecipPot = iwv;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                double dpPa = moistureLayerPressurePa(t);
                if (dpPa <= 1e-6) {
                    iwvNowPrecipPot[i] = 0.0;
                    precipPot[i] = 0.0;
                    precipCap[i] = 0.0;
                    return;
                }

                double qsat = saturationSpecificHumidity(tempPhase[i], t.pressure);
                double q = (iwvNowPrecipPot[i] * g / dpPa) * 1000.0;
                double rel = (qsat > 1e-6) ? (q / qsat) : 0.0;

                double iwvSat = (qsat / 1000.0) * dpPa / g;
                double iwvExcess = Math.max(0.0, iwvNowPrecipPot[i] - iwvSat);
                boolean waterSurface = isWaterSurface(t.surfaceType);

                double precip;
                if (enhancedMode) {
                    double convTerm = clamp(Math.max(0.0, conv[i]) / 7.0, 0.0, 1.1);
                    double[] oro = orographicFactors(t);
                    double instability = clamp((tempPhase[i] + 8.0) / 48.0, 0.0, 1.1);
                    double orogLift = clamp(oro[0] * 0.75 + oro[1] * 0.25 - oro[2] * 0.20, 0.0, 1.2);
                    double largeScale = clamp((rel - 0.82) / 0.30, 0.0, 1.4);

                    // No baseline drizzle: precipitation should emerge from actual moisture dynamics only.
                    precip = (0.42 * largeScale) * (0.65 + 0.35 * instability);
                    precip += convTerm * (0.18 + 0.25 * largeScale);
                    precip += orogLift * (0.10 + 0.20 * largeScale);
                    if (rel > 1.0) precip += (rel - 1.0) * 0.55;
                    precip += iwvExcess * 0.45;
                    precip *= stepScale;

                    double humidityGate = clamp((rel - 0.45) / 0.45, 0.0, 1.0);
                    double sinkFactor = waterSurface
                            ? (0.55 + 0.45 * humidityGate)
                            : (0.50 + 0.50 * humidityGate);
                    precip *= sinkFactor;
                    if (waterSurface) {
                        // Keep more vapor over water so moisture can be exported to land.
                        precip *= 0.84;
                    } else {
                        // Slightly stronger condensation over land.
                        precip *= 1.16;
                    }
                } else {
                    // Physical mode: precipitation only from thermodynamic supersaturation.
                    precip = 0.0;
                    if (rel > 1.0) precip += (rel - 1.0) * 0.55;
                    precip += iwvExcess * 0.45;
                    precip *= stepScale;
                }

                precipPot[i] = Math.max(0.0, precip);
                if (waterSurface) {
                    double capFracWater6h = clamp(0.08 * 6.0, 0.0, 0.95);
                    double capFracWater = scaleFractionByDt(capFracWater6h, stepHours, 6.0);
                    precipCap[i] = Math.max(0.0, iwvNowPrecipPot[i] * capFracWater + iwvExcess * 0.45);
                } else {
                    double capFrac = 0.16;
                    if (enhancedMode) {
                        double onshore = onshoreMoistureIndex(t);
                        double fetch = upwindOceanFetchIndex(t);
                        double tropicalEdge = Math.max(1e-6, tropicalFadeLat());
                        double tropical = clamp((tropicalEdge - Math.abs(t.lat)) / tropicalEdge, 0.0, 1.0);
                        double latEffAbs = Math.abs(t.lat - itczShift);
                        double stormTrack = gaussian(latEffAbs, ferrelEdge - 10.0, 9.0);
                        capFrac += 0.05 * onshore * tropical + 0.08 * fetch * tropical;
                        // Midlatitude storm tracks: bring ocean moisture inland without sharp zonal walls.
                        capFrac += 0.04 * onshore * stormTrack + 0.10 * fetch * stormTrack;
                    }
                    double capFrac6h = clamp(capFrac * 6.0, 0.0, 0.95);
                    double capFracDt = scaleFractionByDt(capFrac6h, stepHours, 6.0);
                    precipCap[i] = Math.max(0.0, iwvNowPrecipPot[i] * capFracDt + iwvExcess * 0.50);
                }
            });

            // Spread local spikes to neighbors to reduce narrow precipitation stripes.
            double[] precipSmooth = enhancedMode
                    ? smoothScalar(tiles, smoothScalar(tiles, precipPot, 0.28), 0.28)
                    : smoothScalar(tiles, precipPot, 0.10);
            final double[] iwvNowPrecipApply = iwv;
            final double[] soilNowPrecipApply = soil;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                double precip = enhancedMode
                        ? (0.68 * precipPot[i] + 0.32 * precipSmooth[i])
                        : (0.88 * precipPot[i] + 0.12 * precipSmooth[i]);
                precip = clamp(precip, 0.0, precipCap[i]);
                precip = Math.min(precip, iwvNowPrecipApply[i]);
                iwvNowPrecipApply[i] = Math.max(0.0, iwvNowPrecipApply[i] - precip);
                if (iterF >= sampleStartIter) {
                    precipTotal[i] += precip;
                }
                double infil = clamp(0.45 + 0.004 * soilNowPrecipApply[i] - 0.02 * maxSlope(t), 0.10, 0.80);
                double runoff = precip * (1.0 - infil);
                if (iterF >= sampleStartIter) {
                    runoffTotal[i] += runoff;
                }
                double soilAdd = precip * soilPerMm * infil;
                soilNowPrecipApply[i] = clamp(soilNowPrecipApply[i] + soilAdd, 0.0, 100.0);
                if (iterF >= sampleStartIter) {
                    soilFromPrecip[i] += soilAdd;
                }
            });

            // 3b) Soil moisture diffusion between neighboring land tiles (mass-conservative on the graph).
            double[] soilBeforeDiff = (iter >= sampleStartIter) ? soil.clone() : null;
            double soilDiffKappa = scaleFractionByDt(0.035, stepHours, 6.0);
            soil = diffuseSoilMoistureConservative(tiles, soil, soilDiffKappa);
            if (iter >= sampleStartIter && soilBeforeDiff != null) {
                final double[] soilNowDiff = soil;
                forEachIndex(n, i -> {
                    soilFromDiff[i] += (soilNowDiff[i] - soilBeforeDiff[i]);
                });
            }

            // Ocean-air coupling: boundary layer over open water should not stay unrealistically dry.
            final double[] iwvNowOcean = iwv;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                if (!isWaterSurface(t.surfaceType)) return;
                double dpPa = moistureLayerPressurePa(t);
                if (dpPa <= 1e-6) return;
                double qsat = saturationSpecificHumidity(tempPhase[i], t.pressure);
                double iwvSat = (qsat / 1000.0) * dpPa / g;
                if (iwvSat <= 1e-9) return;

                double targetRh = enhancedMode ? 0.74 : 0.82;
                double iwvTarget = iwvSat * targetRh;
                if (iwvNowOcean[i] < iwvTarget) {
                    double relax = enhancedMode ? 0.30 : 0.45;
                    iwvNowOcean[i] = iwvNowOcean[i] + (iwvTarget - iwvNowOcean[i]) * relax;
                }
            });

            // 4) qsat cap + mild isotropic diffusion to remove residual checkerboard.
            double[] iwvCap = new double[n];
            final double[] iwvNowCap = iwv;
            forEachIndex(n, i -> {
                Tile t = tiles.get(i);
                double dpPa = moistureLayerPressurePa(t);
                if (dpPa <= 1e-6) {
                    iwvCap[i] = 0.0;
                    return;
                }
                double qsat = saturationSpecificHumidity(tempPhase[i], t.pressure);
                double qCap = Math.min(999.0, qsat * 1.03);
                double iwvMax = (qCap / 1000.0) * dpPa / g;
                double excess = Math.max(0.0, iwvNowCap[i] - iwvMax);
                if (excess > 0.0) {
                    double rainoutBase = enhancedMode ? 0.70 : 0.50;
                    double rainoutFrac = 1.0 - Math.pow(1.0 - rainoutBase, stepHours);
                    double rainout = excess * rainoutFrac;
                    iwvNowCap[i] -= rainout;
                    if (iterF >= sampleStartIter) {
                        precipTotal[i] += rainout;
                    }
                }
                iwvCap[i] = clamp(iwvNowCap[i], 0.0, iwvMax);
            });
            double baseMixKappa = enhancedMode ? 0.14 : 0.08;
            double baseFrontKappa = enhancedMode ? 0.026 : 0.020;
            double mixKappa = scaleFractionByDt(baseMixKappa, stepHours, 6.0);
            double frontKappa = scaleFractionByDt(baseFrontKappa, stepHours, 6.0);
            iwv = mixScalarConservative(tiles, iwvCap, mixKappa);
            iwv = frontEddyMixConservative(tiles, iwv, frontKappa, true);
        }

        final double[] iwvFinal = iwv;
        final double[] soilFinal = soil;
        forEachIndex(n, i -> {
            Tile t = tiles.get(i);
            // Totals accumulate only over the final diagnostic day (stepsPerDay multi-hour iterations).
            t.precipKgM2Day = precipTotal[i];
            t.evapKgM2Day = evapTotal[i];
            t.surfaceRunoffKgM2Day = runoffTotal[i];
            t.precipAvg = clamp((precipTotal[i] / iters) * 2.6, 0.0, 100.0);
            t.evapAvg = clamp((evapTotal[i] / iters) * 2.8, 0.0, 100.0);
            t.atmMoist = atmMoistFromIwv(t, iwvFinal[i], g);
            t.moisture = clamp(soilFinal[i], 0.0, 100.0);
            t.soilStartDiag = soilStart[i];
            t.soilEndDiag = t.moisture;
            t.soilFromPrecipDiag = soilFromPrecip[i];
            t.soilFromEvapDiag = soilFromEvap[i];
            t.soilFromDiffDiag = soilFromDiff[i];
            if (tempDiagCnt[i] > 0) {
                double tMean = tempDiagSum[i] / tempDiagCnt[i];
                t.temperature = (int) Math.round(tMean);
                t.tempMin = tempDiagMin[i];
                t.tempMax = tempDiagMax[i];
            }
        });
    }

    private double[] advectIwvConservative(List<Tile> tiles, double[] src, double dt, ClimateModelMode mode) {
        int n = tiles.size();
        double[] out = new double[n];
        double[] in = new double[n];
        boolean enhancedMode = mode == ClimateModelMode.ENHANCED;
        double dtHours = dt / 3600.0;

        for (int i = 0; i < n; i++) {
            Tile t = tiles.get(i);
            if (t.neighbors == null || t.neighbors.isEmpty()) continue;

            double[] advV = moistureAdvectionVector(t, mode);
            double speedInternal = Math.sqrt(advV[0] * advV[0] + advV[1] * advV[1]);
            if (speedInternal < 1e-6) continue;
            double speedMps = speedInternal * WIND_UNIT_TO_MPS;
            double transportSpeedMps = speedMps * moistureTransportWindBoost(t, mode);

            double advFrac = clamp((transportSpeedMps * dt) / TILE_DIST_M, 0.0, enhancedMode ? 0.34 : 0.24);
            if (advFrac <= 1e-7) continue;

            double wx = advV[0] / (speedInternal + 1e-9);
            double wy = advV[1] / (speedInternal + 1e-9);

            double[] weights = new double[t.neighbors.size()];
            double wSum = 0.0;
            for (int k = 0; k < t.neighbors.size(); k++) {
                Tile nb = t.neighbors.get(k);
                double[] dir = direction(t, nb);
                double dot = wx * dir[0] + wy * dir[1];
                if (dot <= -0.30) {
                    weights[k] = 0.0;
                    continue;
                }

                int uphill = Math.max(0, nb.elevation - t.elevation);
                double pass = 1.0 / (1.0 + 0.15 * uphill);
                double directional = dot > 0.0 ? dot : 0.10 * ((dot + 0.30) / 0.30);
                double w = directional * pass;
                weights[k] = w;
                wSum += w;
            }
            if (wSum <= 1e-9) continue;

            double moved = src[i] * advFrac;
            out[i] += moved;
            for (int k = 0; k < t.neighbors.size(); k++) {
                double w = weights[k];
                if (w <= 0.0) continue;
                int j = t.neighbors.get(k).id;
                if (j < 0 || j >= n) continue;
                in[j] += moved * (w / wSum);
            }
        }

        double[] dst = new double[n];
        for (int i = 0; i < n; i++) {
            dst[i] = src[i] - out[i] + in[i];
            if (dst[i] < 0.0) dst[i] = 0.0;
        }

        // Small crosswind mixing after advection, strictly mass-conservative.
        double mixKappa = scaleFractionByDt(enhancedMode ? 0.045 : 0.030, dtHours, 1.0);
        double[] mixed = mixScalarConservative(tiles, dst, mixKappa);
        // Storm-track eddies enhance cross-latitude moisture exchange near cell boundaries.
        double frontKappa = scaleFractionByDt(enhancedMode ? 0.022 : 0.016, dtHours, 1.0);
        return frontEddyMixConservative(tiles, mixed, frontKappa, true);
    }

    private double[] moistureAdvectionVector(Tile t, ClimateModelMode mode) {
        double vx = t.windX;
        double vy = t.windY;
        if (mode != ClimateModelMode.PHYSICAL) {
            return new double[]{vx, vy};
        }

        // Global-convection proxy for physical mode:
        // add a smooth poleward branch between Hadley and Ferrel without touching wind field itself.
        double latEff = t.lat - itczShift;
        double latAbs = Math.abs(latEff);
        double sign = Math.signum(latEff);
        double start = clamp(hadleyEdge * 0.35, 6.0, 22.0);
        double peak = clamp(hadleyEdge * 0.95, 18.0, 42.0);
        double end = clamp(ferrelEdge + 8.0, 45.0, 78.0);
        double polewardBand = smoothStep(start, peak, latAbs) * (1.0 - smoothStep(ferrelEdge, end, latAbs));
        double tiltScale = clamp(0.80 + Math.abs(hadleyEdge - 30.0) / 18.0, 0.80, 1.45);
        double polewardMps = 2.8 * polewardBand * tiltScale;
        vy += sign * (polewardMps / WIND_UNIT_TO_MPS);
        return new double[]{vx, vy};
    }


    private double[] smoothScalar(List<Tile> tiles, double[] src, double kappa) {
        int n = tiles.size();
        double[] out = new double[n];
        forEachIndex(n, i -> {
            Tile t = tiles.get(i);
            if (t.neighbors == null || t.neighbors.isEmpty()) {
                out[i] = src[i];
                return;
            }
            double sum = 0.0;
            int cnt = 0;
            for (Tile nb : t.neighbors) {
                sum += src[nb.id];
                cnt++;
            }
            double avg = (cnt > 0) ? (sum / cnt) : src[i];
            out[i] = src[i] * (1.0 - kappa) + avg * kappa;
        });
        return out;
    }

    private double[] mixScalarConservative(List<Tile> tiles, double[] src, double kappa) {
        int n = tiles.size();
        double[] out = src.clone();
        for (int i = 0; i < n; i++) {
            Tile ti = tiles.get(i);
            if (ti.neighbors == null || ti.neighbors.isEmpty()) continue;
            int degI = ti.neighbors.size();
            for (Tile nb : ti.neighbors) {
                int j = nb.id;
                if (j <= i || j < 0 || j >= n) continue;
                Tile tj = tiles.get(j);
                int degJ = (tj.neighbors == null) ? 0 : tj.neighbors.size();
                int deg = Math.max(1, Math.max(degI, degJ));
                double flux = (src[i] - src[j]) * (kappa / deg);
                out[i] -= flux;
                out[j] += flux;
            }
        }
        forEachIndex(n, i -> {
            if (out[i] < 0.0) out[i] = 0.0;
        });
        return out;
    }

    private double[] frontEddyMixConservative(List<Tile> tiles, double[] src, double kappaBase, boolean floorZero) {
        int n = tiles.size();
        double[] out = src.clone();
        for (int i = 0; i < n; i++) {
            Tile ti = tiles.get(i);
            if (ti.neighbors == null || ti.neighbors.isEmpty()) continue;
            int degI = ti.neighbors.size();
            double eI = frontEddyStrength(ti);
            for (Tile nb : ti.neighbors) {
                int j = nb.id;
                if (j <= i || j < 0 || j >= n) continue;
                Tile tj = tiles.get(j);
                int degJ = (tj.neighbors == null) ? 0 : tj.neighbors.size();
                int deg = Math.max(1, Math.max(degI, degJ));

                double eJ = frontEddyStrength(tj);
                double edgeEddy = Math.max(eI, eJ);
                double kappa = kappaBase * (0.35 + 0.65 * edgeEddy);
                double flux = (src[i] - src[j]) * (kappa / deg);
                out[i] -= flux;
                out[j] += flux;
            }
        }
        if (floorZero) {
            forEachIndex(n, i -> {
                if (out[i] < 0.0) out[i] = 0.0;
            });
        }
        return out;
    }

    private double[] diffuseSoilMoistureConservative(List<Tile> tiles, double[] src, double kappaBase) {
        int n = tiles.size();
        double[] out = src.clone();
        for (int i = 0; i < n; i++) {
            Tile ti = tiles.get(i);
            if (ti.neighbors == null || ti.neighbors.isEmpty()) continue;
            if (isWaterSurface(ti.surfaceType)) continue;
            int degI = ti.neighbors.size();
            for (Tile nb : ti.neighbors) {
                int j = nb.id;
                if (j <= i || j < 0 || j >= n) continue;
                Tile tj = tiles.get(j);
                if (isWaterSurface(tj.surfaceType)) continue;

                int degJ = (tj.neighbors == null) ? 0 : tj.neighbors.size();
                int deg = Math.max(1, Math.max(degI, degJ));
                double slope = Math.abs(ti.elevation - tj.elevation);
                double kappa = kappaBase / (1.0 + 0.20 * slope);
                if (isWetlandSurface(ti.surfaceType) || isWetlandSurface(tj.surfaceType) || ti.isRiver || tj.isRiver) {
                    kappa *= 0.60;
                }
                double flux = (src[i] - src[j]) * (kappa / deg);
                out[i] -= flux;
                out[j] += flux;
            }
        }
        forEachIndex(n, i -> {
            out[i] = clamp(out[i], 0.0, 100.0);
        });
        return out;
    }

    private double moistureTransportWindBoost(Tile t, ClimateModelMode mode) {
        if (mode == ClimateModelMode.PHYSICAL) {
            // Slightly stronger long-range moisture export around subtropical/ferrel transition.
            double latAbs = Math.abs(t.lat - itczShift);
            double stormTrack = gaussian(latAbs, ferrelEdge - 8.0, 10.0);
            double subtropical = smoothStep(hadleyEdge * 0.55, hadleyEdge * 1.05, latAbs)
                    * (1.0 - smoothStep(ferrelEdge + 6.0, ferrelEdge + 16.0, latAbs));
            return clamp(1.0 + 0.10 * subtropical + 0.12 * stormTrack, 1.0, 1.28);
        }
        double elevNorm = clamp(t.elevation / 8.0, 0.0, 1.0);
        return 1.15 + 0.35 * elevNorm;
    }

    private double frontEddyStrength(Tile t) {
        double latAbs = Math.abs(t.lat - itczShift);
        double hadleyFront = gaussian(latAbs, hadleyEdge, 7.5);
        double ferrelFront = gaussian(latAbs, ferrelEdge, 8.0);
        double base = clamp(hadleyFront * 0.75 + ferrelFront, 0.0, 1.0);
        double seasonHemi = seasonalHemisphereIndex(t.lat);
        double seasonStrength = seasonalInfluenceStrength(t.lat);
        // Winter hemisphere tends to stronger baroclinic eddies near fronts.
        double winterBoost = 1.0 + 0.22 * seasonStrength * clamp(-seasonHemi, 0.0, 1.0);
        double summerDamp = 1.0 - 0.12 * seasonStrength * clamp(seasonHemi, 0.0, 1.0);
        return clamp(base * winterBoost * summerDamp, 0.0, 1.35);
    }

    private double seasonalHemisphereIndex(double lat) {
        double shift = clamp(itczShift, -90.0, 90.0);
        double s = Math.signum(shift);
        if (Math.abs(s) < 1e-9) return 0.0;
        double h = Math.signum(lat);
        if (Math.abs(h) < 1e-9) return 0.0;
        // +1: same hemisphere as ITCZ shift (summer), -1: opposite (winter).
        return h * s;
    }

    private double seasonalInfluenceStrength(double lat) {
        double shiftMag = Math.abs(clamp(itczShift, -90.0, 90.0));
        // 0 around equinox / low tilt forcing, 1 for strong seasonal displacement.
        double shiftScale = clamp(shiftMag / 23.5, 0.0, 1.25);
        // Keep equator almost unaffected, stronger toward subtropics/mid-lats.
        double latAbs = Math.abs(lat);
        double latScale = smoothStep(8.0, 30.0, latAbs);
        return clamp(shiftScale * latScale, 0.0, 1.0);
    }

    private double seasonalBeltBlendStrength() {
        double shiftMag = Math.abs(clamp(itczShift, -90.0, 90.0));
        double shiftScale = clamp(shiftMag / 35.0, 0.0, 1.0);
        // Keep seasonal deviations moderate versus equinox baseline.
        return 0.20 * shiftScale;
    }

    private double tropicalCoreLat() {
        return clamp(hadleyEdge * 0.50, 10.0, 22.0);
    }

    private double tropicalFadeLat() {
        return clamp(hadleyEdge * 0.82, 18.0, 34.0);
    }

    private double midLatZonalStart() {
        return clamp(hadleyEdge - 2.0, 20.0, 42.0);
    }

    private double midLatZonalEnd() {
        return clamp(ferrelEdge + 2.0, 46.0, 76.0);
    }

    private double smoothStep(double edge0, double edge1, double x) {
        if (edge1 <= edge0) return (x <= edge0) ? 0.0 : 1.0;
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private double[] pressureGradientVector(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return new double[]{0.0, 0.0};
        double gx = 0.0;
        double gy = 0.0;
        for (Tile nb : t.neighbors) {
            double[] dir = direction(t, nb);
            double dp = t.pressure - nb.pressure; // >0 => acceleration towards neighbor
            double dist = planarDistanceKm(t, nb);
            double w = 1.0 / Math.max(30.0, dist);
            gx += dir[0] * dp * w;
            gy += dir[1] * dp * w;
        }
        return new double[]{gx / t.neighbors.size(), gy / t.neighbors.size()};
    }

    private double[] thermalGradientVector(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return new double[]{0.0, 0.0};
        double gx = 0.0;
        double gy = 0.0;
        for (Tile nb : t.neighbors) {
            double[] dir = direction(t, nb);
            double dT = t.temperature - nb.temperature;
            gx += dir[0] * dT;
            gy += dir[1] * dT;
        }
        return new double[]{gx / t.neighbors.size(), gy / t.neighbors.size()};
    }

    private double[] terrainSteeringVector(Tile t, double vx, double vy) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return new double[]{0.0, 0.0};

        double mag = Math.sqrt(vx * vx + vy * vy);
        double ux = (mag > 1e-6) ? (vx / mag) : 0.0;
        double uy = (mag > 1e-6) ? (vy / mag) : 0.0;

        double tx = 0.0;
        double ty = 0.0;
        double wSum = 0.0;
        for (Tile nb : t.neighbors) {
            double[] dir = direction(t, nb);
            double along = (mag > 1e-6) ? Math.max(0.0, ux * dir[0] + uy * dir[1]) : 1.0;
            int uphill = Math.max(0, nb.elevation - t.elevation);
            int downhill = Math.max(0, t.elevation - nb.elevation);

            // Prefer passable paths and mild downhill channels (valleys).
            double pass = 1.0 / (1.0 + 0.17 * uphill);
            double valleyBoost = 1.0 + 0.05 * Math.min(8, downhill);
            double w = (0.2 + along) * pass * valleyBoost;

            tx += dir[0] * w;
            ty += dir[1] * w;
            wSum += w;
        }
        if (wSum <= 1e-9) return new double[]{0.0, 0.0};

        tx /= wSum;
        ty /= wSum;
        double tMag = Math.sqrt(tx * tx + ty * ty);
        if (tMag <= 1e-9) return new double[]{0.0, 0.0};

        tx /= tMag;
        ty /= tMag;

        if (mag <= 1e-6) {
            return new double[]{tx, ty};
        }
        // Steering correction vector.
        return new double[]{tx - ux, ty - uy};
    }

    private double divergence(Tile tile) {
        if (tile.neighbors == null || tile.neighbors.isEmpty()) return 0.0;
        double div = 0.0;
        int n = 0;
        for (Tile nb : tile.neighbors) {
            double[] dir = direction(tile, nb);
            double vdot = tile.windX * dir[0] + tile.windY * dir[1];
            div += vdot;
            n++;
        }
        return (n == 0) ? 0.0 : (div / n);
    }

    private double windShearIndex(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double sum = 0.0;
        int n = 0;
        for (Tile nb : t.neighbors) {
            double dvx = t.windX - nb.windX;
            double dvy = t.windY - nb.windY;
            sum += Math.sqrt(dvx * dvx + dvy * dvy);
            n++;
        }
        return (n == 0) ? 0.0 : (sum / n);
    }

    private double moistureLayerPressurePa(Tile t) {
        double pSfcHpa = Math.max(0.0, t.pressure);
        if (pSfcHpa <= 0.0) return 0.0;

        // Moisture exchange is dominated by the lower troposphere / boundary layer,
        // not by the full atmospheric column down to a fixed pressure top.
        // Use full atmospheric column as active moisture reservoir.
        double dpHpa = clamp(pSfcHpa * 1.00, 220.0, 1200.0);
        dpHpa = Math.min(dpHpa, pSfcHpa);
        return dpHpa * 100.0;
    }

    private int climateStepHours() {
        Integer hObj = Integer.getInteger("planet.climate.stepHours");
        int h = (hObj == null) ? DEFAULT_CLIMATE_STEP_HOURS : hObj;
        return (int) clamp(h, 1, 24);
    }

    private double scaleFractionByDt(double fracRef, double dtHours, double refHours) {
        double f = clamp(fracRef, 0.0, 0.999999);
        if (f <= 0.0) return 0.0;
        double ratio = Math.max(0.0, dtHours / Math.max(1e-9, refHours));
        return clamp(1.0 - Math.pow(1.0 - f, ratio), 0.0, 0.999999);
    }


    private double iwvFromAtmMoist(Tile t, double g) {
        double dpPa = moistureLayerPressurePa(t);
        if (dpPa <= 1e-6) return 0.0;
        double q = Math.max(0.0, Double.isNaN(t.atmMoist) ? 0.0 : t.atmMoist) / 1000.0;
        return q * dpPa / g;
    }

    private double atmMoistFromIwv(Tile t, double iwv, double g) {
        double dpPa = moistureLayerPressurePa(t);
        if (dpPa <= 1e-6) return 0.0;
        double q = (iwv * g / dpPa) * 1000.0;
        double qsat = saturationSpecificHumidity(t);
        double qMax = Math.min(999.0, qsat * 1.03);
        return clamp(q, 0.0, qMax);
    }

    private double saturationVaporPressure(double tempC) {
        // kPa; piecewise fit keeps behavior stable from cryogenic to super-hot climates.
        if (tempC <= 0.0) {
            // Buck (ice), good around sub-zero temperatures.
            return 0.61115 * Math.exp((23.036 - tempC / 333.7) * (tempC / (279.82 + tempC)));
        }
        if (tempC <= 99.0) {
            // Antoine, 1..100 C
            double log10mmHg = 8.07131 - (1730.63 / (233.426 + tempC));
            return Math.pow(10.0, log10mmHg) * 0.133322;
        }
        if (tempC <= 374.0) {
            // Antoine, 99..374 C
            double log10mmHg = 8.14019 - (1810.94 / (244.485 + tempC));
            return Math.pow(10.0, log10mmHg) * 0.133322;
        }
        // Above critical point: keep monotonic growth without singularity.
        double tc = tempC - 374.0;
        return 22064.0 * (1.0 + 0.0025 * tc);
    }

    private double saturationSpecificHumidity(Tile t) {
        double p = pressureKPa(t.pressure);
        if (p <= 1e-6) return 0.0;

        double es = Math.max(0.0, saturationVaporPressure(t.temperature));
        // Vapor partial pressure cannot exceed ambient pressure.
        double esEff = Math.min(es, p * 0.98);
        double denom = Math.max(1e-6, p - 0.378 * esEff);
        double q = 0.622 * esEff / denom;
        return clamp(q * 1000.0, 0.0, 999.0);
    }

    private double saturationSpecificHumidity(double tempC, int pressureUnits) {
        double p = pressureKPa(pressureUnits);
        if (p <= 1e-6) return 0.0;
        double es = Math.max(0.0, saturationVaporPressure(tempC));
        double esEff = Math.min(es, p * 0.98);
        double denom = Math.max(1e-6, p - 0.378 * esEff);
        double q = 0.622 * esEff / denom;
        return clamp(q * 1000.0, 0.0, 999.0);
    }

    private double pressureKPa(int pressureUnits) {
        double atm = Math.max(0.05, pressureUnits / 1000.0);
        return atm * 101.325;
    }

    private double diurnalPhaseTemperatureC(Tile t,
                                            double soilMoist,
                                            int iter,
                                            int stepsPerDay,
                                            int stepHours,
                                            double atmDensity) {
        double localStartHour = (iter * (double) stepHours) + t.lon / 15.0;
        // Mean diurnal phase over the whole integration window [t, t + stepHours].
        double phase = meanDiurnalCos(localStartHour, stepHours, 14.0);
        boolean water = isWaterSurface(t.surfaceType);
        double latAbs = Math.abs(t.lat);
        double latFactor = clamp(0.25 + 0.75 * Math.cos(Math.toRadians(latAbs)), 0.20, 1.0);
        double atmDamp = clamp(1.0 / (1.0 + 0.55 * atmDensity), 0.22, 0.95);
        double ampBase = water ? 2.2 : 8.0;
        double amp = ampBase * latFactor * atmDamp;
        if (water) {
            return t.temperature + phase * amp;
        }

        // Drier land has much stronger night cooling and wider diurnal range.
        double soilFrac = clamp(soilMoist / 100.0, 0.0, 1.0);
        double dryness = 1.0 - soilFrac;
        double dayAmp = amp * (0.84 + 0.18 * dryness);
        double nightAmp = amp * (1.06 + 3.10 * dryness);
        double anomaly = (phase >= 0.0) ? (phase * dayAmp) : (phase * nightAmp);
        return t.temperature + anomaly;
    }

    private double meanDiurnalCos(double localStartHour, double durationHours, double peakHour) {
        double omega = 2.0 * Math.PI / 24.0;
        double a = omega * (localStartHour - peakHour);
        double b = omega * (localStartHour + durationHours - peakHour);
        double denom = omega * Math.max(1e-9, durationHours);
        return (Math.sin(b) - Math.sin(a)) / denom;
    }

    private double[] orographicFactors(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return new double[]{0.0, 0.0, 0.0};
        double mag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        if (mag < 1e-6) return new double[]{0.0, 0.0, 0.0};

        double wx = t.windX / mag;
        double wy = t.windY / mag;
        double windward = 0.0;
        double leeward = 0.0;
        for (Tile nb : t.neighbors) {
            double[] dir = direction(t, nb);
            double dot = wx * dir[0] + wy * dir[1];
            int diff = nb.elevation - t.elevation;
            if (diff <= 0) continue;
            if (dot > 0.2) windward = Math.max(windward, diff / 10.0);
            if (dot < -0.2) leeward = Math.max(leeward, diff / 10.0);
        }

        double shadow = upwindShadow(t, wx, wy);
        windward = clamp(windward, 0.0, 1.6);
        leeward = clamp(leeward, 0.0, 1.6);
        shadow = clamp(shadow, 0.0, 1.6);
        return new double[]{windward, leeward, shadow};
    }

    private double upwindShadow(Tile t, double wx, double wy) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double best = 0.0;
        double windMag = Math.sqrt(t.windX * t.windX + t.windY * t.windY) * WIND_UNIT_TO_MPS;

        int maxDepth = 3;
        if (windMag > 16.0) maxDepth = 4;
        if (windMag > 35.0) maxDepth = 5;
        if (windMag > 65.0) maxDepth = 6;

        for (Tile nb : t.neighbors) {
            double[] dir = direction(t, nb);
            double dot = wx * dir[0] + wy * dir[1];
            if (dot > -0.2) continue;
            int diff = nb.elevation - t.elevation;
            if (diff <= 0) continue;
            best = Math.max(best, diff / 10.0);
            best = Math.max(best, upwindChain(nb, wx, wy, t.elevation, maxDepth - 1, 1));
        }
        return best;
    }

    private double upwindChain(Tile start, double wx, double wy, int baseElev, int depthLeft, int step) {
        if (start.neighbors == null || depthLeft <= 0) return 0.0;
        double best = 0.0;
        double decay = 1.0 / (1.0 + 0.55 * step);

        for (Tile nb : start.neighbors) {
            double[] dir = direction(start, nb);
            double dot = wx * dir[0] + wy * dir[1];
            if (dot > -0.2) continue;
            int diff = nb.elevation - baseElev;
            if (diff > 0) {
                best = Math.max(best, (diff / 10.0) * decay);
            }
            best = Math.max(best, upwindChain(nb, wx, wy, baseElev, depthLeft - 1, step + 1));
        }
        return best;
    }

    private double onshoreMoistureIndex(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double mag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        if (mag < 1e-6) return 0.0;

        double wx = t.windX / (mag + 1e-9);
        double wy = t.windY / (mag + 1e-9);
        double sum = 0.0;
        double wsum = 0.0;
        for (Tile nb : t.neighbors) {
            double[] dir = direction(nb, t); // upwind inflow direction
            double inflow = wx * dir[0] + wy * dir[1];
            if (inflow <= 0.05) continue;
            double w = clamp(inflow, 0.0, 1.0);
            wsum += w;
            if (isWaterSurface(nb.surfaceType)) {
                sum += w;
            }
        }
        if (wsum <= 1e-9) return 0.0;
        return clamp(sum / wsum, 0.0, 1.0);
    }

    private double upwindOceanFetchIndex(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        double mag = Math.sqrt(t.windX * t.windX + t.windY * t.windY);
        if (mag < 1e-6) return 0.0;

        double wx = t.windX / (mag + 1e-9);
        double wy = t.windY / (mag + 1e-9);
        Tile cur = t;
        double score = 0.0;
        double wsum = 0.0;

        for (int step = 1; step <= 4; step++) {
            if (cur.neighbors == null || cur.neighbors.isEmpty()) break;
            Tile best = null;
            double bestInflow = 0.0;
            for (Tile nb : cur.neighbors) {
                double[] dir = direction(nb, cur); // inflow direction into current point
                double inflow = wx * dir[0] + wy * dir[1];
                if (inflow > bestInflow) {
                    bestInflow = inflow;
                    best = nb;
                }
            }
            if (best == null || bestInflow <= 0.05) break;

            double w = Math.pow(0.74, step - 1) * clamp(bestInflow, 0.0, 1.0);
            wsum += w;
            if (isWaterSurface(best.surfaceType)) {
                score += w;
            }
            cur = best;
        }

        if (wsum <= 1e-9) return 0.0;
        return clamp(score / wsum, 0.0, 1.0);
    }

    private boolean isForestSurface(SurfaceType st) {
        return switch (st) {
            case PLAINS_FOREST, FOREST, RAINFOREST,
                    HILLS_FOREST, HILLS_RAINFOREST, MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST,
                    RIDGE_FOREST, CANYON_FOREST, BASIN_FOREST -> true;
            default -> false;
        };
    }

    private boolean isWaterSurface(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    SHALLOW_SEA,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP,
                    STEAM_SEA -> true;
            default -> false;
        };
    }

    private boolean isWetlandSurface(SurfaceType st) {
        return switch (st) {
            case SWAMP, MUD_SWAMP, BASIN_SWAMP -> true;
            default -> false;
        };
    }

    private boolean hasWaterNeighbor(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return false;
        for (Tile nb : t.neighbors) {
            if (nb == null) continue;
            if (isWaterSurface(nb.surfaceType)) return true;
        }
        return false;
    }

    private double[] direction(Tile from, Tile to) {
        double dLon = lonDeltaDeg(from.lon, to.lon);
        double dx = dLon * 111.0 * Math.cos(Math.toRadians(from.lat));
        double dy = (to.lat - from.lat) * 111.0;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return new double[]{0.0, 0.0};
        return new double[]{dx / len, dy / len};
    }

    private double planarDistanceKm(Tile a, Tile b) {
        double dLon = lonDeltaDeg(a.lon, b.lon);
        double dx = dLon * 111.0 * Math.cos(Math.toRadians(a.lat));
        double dy = (b.lat - a.lat) * 111.0;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double lonDeltaDeg(double fromLon, double toLon) {
        double d = toLon - fromLon;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private double[] directionTo(double lat, double lon, double toLat, double toLon) {
        double dLat = toLat - lat;
        double dLon = lonDeltaDeg(lon, toLon) * Math.cos(Math.toRadians(lat));
        double len = Math.sqrt(dLat * dLat + dLon * dLon);
        if (len < 1e-6) return new double[]{0.0, 0.0};
        return new double[]{dLon / len, dLat / len};
    }

    private double angularDistanceDeg(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lonDeltaDeg(lon1, lon2));
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return Math.toDegrees(c);
    }

    private double[] limitVector(double x, double y, double maxMag) {
        double mag = Math.sqrt(x * x + y * y);
        if (mag <= maxMag) return new double[]{x, y};
        // Soft saturation preserves dynamic range near cap better than hard clipping.
        double target = maxMag * Math.tanh(mag / maxMag);
        double k = target / (mag + 1e-9);
        return new double[]{x * k, y * k};
    }

    private double surfaceDrag(SurfaceType st) {
        return switch (st) {
            case OCEAN, OPEN_WATER_SHALLOW, OPEN_WATER_DEEP -> 0.07;
            case ICE_OCEAN, SEA_ICE_SHALLOW, SEA_ICE_DEEP, ICE, GLACIER -> 0.10;
            case PLAINS, PLAINS_GRASS, GRASSLAND, SAVANNA, DRY_SAVANNA -> 0.12;
            case FOREST, PLAINS_FOREST, RAINFOREST, HILLS_FOREST, HILLS_RAINFOREST, MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST -> 0.18;
            case HILLS, HILLS_GRASS, HILLS_DRY_SAVANNA, HILLS_SAVANNA -> 0.16;
            case MOUNTAINS, HIGH_MOUNTAINS, MOUNTAINS_SNOW, RIDGE, RIDGE_ROCK, RIDGE_SNOW -> 0.22;
            default -> 0.14;
        };
    }

    private double density(int pressure, int temperature) {
        double tKelvin = temperature + 273.15;
        if (tKelvin <= 1e-6) tKelvin = 1.0;
        return pressure / tKelvin;
    }

    private long windSeed(PlanetConfig planet, long baseSeed) {
        long h = 0xCBF29CE484222325L;
        h = mix(h, baseSeed);
        h = mix(h, Double.doubleToLongBits(planet.radiusKm));
        h = mix(h, Double.doubleToLongBits(planet.meanTemperature));
        h = mix(h, Double.doubleToLongBits(planet.atmosphereDensity));
        h = mix(h, planet.atmosphericPressure);
        h = mix(h, planet.waterCoverageOrdinal);
        h = mix(h, planet.rotationPrograde);
        h = mix(h, Double.doubleToLongBits(planet.rotationPeriodHours));
        h = mix(h, Double.doubleToLongBits(planet.axialTilt));
        return h;
    }

    private long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001B3L;
        return h;
    }

    private double noise01(long seed, int id) {
        long x = seed + id * 0x9E3779B97F4A7C15L;
        x ^= (x >>> 27);
        x *= 0x3C79AC492BA7B653L;
        x ^= (x >>> 33);
        x *= 0x1C69B3F74AC4AE35L;
        x ^= (x >>> 27);
        long v = x & 0xFFFFFFFFL;
        return v / (double) 0xFFFFFFFFL;
    }

    private double gaussian(double x, double mu, double sigma) {
        double d = (x - mu) / sigma;
        return Math.exp(-0.5 * d * d);
    }

    private double maxSlope(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        int e = t.elevation;
        int max = 0;
        for (Tile n : t.neighbors) {
            int d = Math.abs(e - n.elevation);
            if (d > max) max = d;
        }
        return max;
    }

    private void convertWindsToMetersPerSecond(List<Tile> tiles) {
        for (Tile t : tiles) {
            t.windX *= WIND_UNIT_TO_MPS;
            t.windY *= WIND_UNIT_TO_MPS;
        }
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
