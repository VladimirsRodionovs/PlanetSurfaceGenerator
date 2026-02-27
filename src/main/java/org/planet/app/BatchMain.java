package org.planet.app;

import org.planet.core.db.DataSourceFactory;
import org.planet.core.db.DbConfig;
import org.planet.core.db.LocalDbConfigLoader;
import org.planet.core.db.MoonTideResolver;
import org.planet.core.db.PlanetConfigMapper;
import org.planet.core.db.PlanetSurfaceRepository;
import org.planet.core.db.StarSystemRepository;
import org.planet.core.db.dto.StarSystemObjectRow;
import org.planet.core.generation.ConsoleStageListener;
import org.planet.core.generation.GenerationPipeline;
import org.planet.core.generation.StageProfile;
import org.planet.core.generation.WorldClassifier;
import org.planet.core.generation.WorldType;
import org.planet.core.generation.PlanetTuning;
import org.planet.core.generation.ResourcePresence;
import org.planet.core.generation.ResourceType;
import org.planet.core.generation.ResourceStatsReport;
import org.planet.core.io.CsvTileLoader;
import org.planet.core.io.PlanetSurfaceSerializer;
import org.planet.core.io.TileSetSelector;
import org.planet.core.model.BiomeModifier;
import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BatchMain {
    private static final Path BATCH_LOG_FILE = Paths.get(
            "/home/vladimirs/PlanetSurfaceGenerator/planet-generator/batch_generation.log"
    );

    public static void main(String[] args) {
        if (args.length >= 2 && "--dump-request".equals(args[0])) {
            runDumpRequestMode(args);
            return;
        }

        int from = 2;
        int to = 50_001;
        List<String> positional = collectPositionalArgs(args);
        if (positional.size() >= 1) from = Integer.parseInt(positional.get(0));
        if (positional.size() >= 2) to = Integer.parseInt(positional.get(1));

        DbConfig cfg = buildDbConfig(args);

        DataSource ds = DataSourceFactory.create(cfg);
        StarSystemRepository repo = new StarSystemRepository(ds, cfg.starSystemsTable);
        PlanetSurfaceRepository surfaceRepo = new PlanetSurfaceRepository(ds, cfg.planetSurfacesTable);

        long batchStartMs = System.currentTimeMillis();
        int systemsOk = 0;
        int systemsFail = 0;
        appendBatchLog(BATCH_LOG_FILE, "[BATCH_START] from=" + from + " to=" + to
                + " parallel=" + System.getProperty("planet.climate.parallel", "true")
                + " srcTable=" + cfg.starSystemsTable
                + " dstTable=" + cfg.planetSurfacesTable);
        for (int sys = from; sys <= to; sys++) {
            long sysStartMs = System.currentTimeMillis();
            int planetsOk = 0;
            int planetsFail = 0;
            try {
                List<StarSystemObjectRow> candidates = repo.listCandidates(sys);
                if (candidates.isEmpty()) {
                    appendBatchLog(BATCH_LOG_FILE, "[SYS_SKIP] sys=" + sys
                            + " candidates=0 durMs=" + (System.currentTimeMillis() - sysStartMs));
                    continue;
                }
                System.out.println("StarSystem_" + sys + " candidates: " + candidates.size());
                appendBatchLog(BATCH_LOG_FILE, "[SYS_START] sys=" + sys + " candidates=" + candidates.size());

                for (StarSystemObjectRow candidate : candidates) {
                    try {
                        StarSystemObjectRow row = repo.loadObjectRow(sys, candidate.objectInternalId);
                        if (row == null) continue;

                        PlanetConfig planet = PlanetConfigMapper.fromDescription(row);
                        MoonTideResolver.populateMoonTideSources(planet, row, repo);
                        String tilesPath = TileSetSelector.pickTilesPath(planet.radiusKm);
                        List<Tile> tiles = CsvTileLoader.load(tilesPath);

                        WorldType worldType = WorldClassifier.classify(planet);

                        if (planet.lavaWorld) {
                            for (Tile t : tiles) {
                                t.volcanism = 100;
                                t.temperature = 800;
                            }
                        }

                        long seed = computeSeed(row);
                        GeneratorSettings settings = new GeneratorSettings(seed);
                        settings.seed = seed;
                        PlanetTuning.apply(settings, planet, worldType);

                        StageProfile profile = WorldClassifier.profileFor(worldType, planet);
                        GenerationPipeline pipeline =
                                new GenerationPipeline(
                                        2.5,
                                        0.7, 0.3, 0.15,
                                        profile,
                                        true,
                                        new ConsoleStageListener()
                                );
                        int plateCount = PlanetTuning.plateCount(planet, worldType);
                        pipeline.run(tiles, planet, settings, plateCount);

                        String hexJson = PlanetSurfaceSerializer.toJson(tiles, planet);
                        PlanetSurfaceRepository.UpsertReceipt receipt = surfaceRepo.upsertSurface(
                                row.starSysIdx,
                                row.objectInternalId,
                                row.objectName,
                                computeSeed(row),
                                hexJson
                        );
                        planetsOk++;
                        appendBatchLog(BATCH_LOG_FILE, "[PLANET_OK] sys=" + sys
                                + " obj=" + candidate.objectInternalId
                                + " rows=" + receipt.rowsAffected
                                + " bytes=" + receipt.charLength
                                + " jsonKeys=" + receipt.jsonLength);
                    } catch (Exception ex) {
                        planetsFail++;
                        System.out.println("Failed to generate/save planet " + candidate.objectInternalId +
                                " in StarSystem_" + sys + ": " + ex.getMessage());
                        appendBatchLog(BATCH_LOG_FILE, "[PLANET_FAIL] sys=" + sys
                                + " obj=" + candidate.objectInternalId
                                + " msg=" + sanitizeLogMessage(ex.getMessage()));
                    }
                }
                systemsOk++;
                appendBatchLog(BATCH_LOG_FILE, "[SYS_DONE] sys=" + sys
                        + " ok=" + planetsOk
                        + " fail=" + planetsFail
                        + " durMs=" + (System.currentTimeMillis() - sysStartMs));
            } catch (Exception ex) {
                systemsFail++;
                System.out.println("Failed StarSystem_" + sys + ": " + ex.getMessage());
                appendBatchLog(BATCH_LOG_FILE, "[SYS_FAIL] sys=" + sys
                        + " ok=" + planetsOk
                        + " fail=" + planetsFail
                        + " durMs=" + (System.currentTimeMillis() - sysStartMs)
                        + " msg=" + sanitizeLogMessage(ex.getMessage()));
            }
        }
        appendBatchLog(BATCH_LOG_FILE, "[BATCH_DONE] from=" + from
                + " to=" + to
                + " systemsOk=" + systemsOk
                + " systemsFail=" + systemsFail
                + " durMs=" + (System.currentTimeMillis() - batchStartMs));
    }

    private static void runDumpRequestMode(String[] args) {
        Path requestFile = Paths.get(args[1]);
        Path outDir = Paths.get("/home/vladimirs/PlanetSurfaceGenerator/planet-generator");
        for (int i = 2; i < args.length; i++) {
            if ("--out-dir".equals(args[i]) && i + 1 < args.length) {
                outDir = Paths.get(args[i + 1]);
                i++;
            }
        }

        DbConfig cfg = buildDbConfig(args);

        DataSource ds = DataSourceFactory.create(cfg);
        StarSystemRepository repo = new StarSystemRepository(ds, cfg.starSystemsTable);

        List<RequestRow> requests;
        try {
            requests = parseRequestFile(requestFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse request file: " + requestFile, e);
        }
        if (requests.isEmpty()) {
            System.out.println("[DUMP-BATCH] No requests found in " + requestFile);
            return;
        }

        System.out.println("[DUMP-BATCH] Requests: " + requests.size());
        int ok = 0;
        int fail = 0;
        for (RequestRow req : requests) {
            try {
                StarSystemObjectRow row = repo.loadObjectRow(req.starSysIdx, req.objectInternalId);
                if (row == null) {
                    throw new IllegalStateException("Object not found");
                }
                PlanetConfig planet = PlanetConfigMapper.fromDescription(row);
                MoonTideResolver.populateMoonTideSources(planet, row, repo);
                List<Tile> tiles = generateTiles(row, planet);
                Path dump = dumpTilesForDebug(outDir, row, planet, tiles);
                System.out.println("[DUMP-BATCH] OK sys=" + req.starSysIdx + " obj=" + req.objectInternalId + " -> " + dump);
                ok++;
            } catch (Exception ex) {
                System.err.println("[DUMP-BATCH] FAIL sys=" + req.starSysIdx + " obj=" + req.objectInternalId + " : " + ex.getMessage());
                fail++;
            }
        }
        System.out.println("[DUMP-BATCH] Done. ok=" + ok + " fail=" + fail);
    }

    private static List<Tile> generateTiles(StarSystemObjectRow row, PlanetConfig planet) {
        String tilesPath = TileSetSelector.pickTilesPath(planet.radiusKm);
        List<Tile> tiles = CsvTileLoader.load(tilesPath);

        WorldType worldType = WorldClassifier.classify(planet);
        if (planet.lavaWorld) {
            for (Tile t : tiles) {
                t.volcanism = 100;
                t.temperature = 800;
            }
        }

        long seed = computeSeed(row);
        GeneratorSettings settings = new GeneratorSettings(seed);
        settings.seed = seed;
        PlanetTuning.apply(settings, planet, worldType);

        StageProfile profile = WorldClassifier.profileFor(worldType, planet);
        GenerationPipeline pipeline =
                new GenerationPipeline(
                        2.5,
                        0.7, 0.3, 0.15,
                        profile,
                        true,
                        new ConsoleStageListener()
                );
        int plateCount = PlanetTuning.plateCount(planet, worldType);
        pipeline.run(tiles, planet, settings, plateCount);
        return tiles;
    }

    private static DbConfig buildDbConfig(String[] args) {
        DbConfig cfg = new DbConfig();
        cfg.jdbcUrl = "jdbc:mysql://localhost:3306/EXOLOG";
        cfg.user = "ghost_reg";
        cfg.password = "";
        LocalDbConfigLoader.apply(cfg);
        cfg.applyOverridesFromSystem();

        cfg.jdbcUrl = pick(findOptionValue(args, "--db-url"), cfg.jdbcUrl);
        cfg.user = pick(findOptionValue(args, "--db-user"), cfg.user);
        cfg.password = pick(findOptionValue(args, "--db-password"), cfg.password);
        cfg.starSystemsTable = pick(findOptionValue(args, "--star-table"), cfg.starSystemsTable);
        cfg.planetSurfacesTable = pick(findOptionValue(args, "--surface-table"), cfg.planetSurfacesTable);
        return cfg;
    }

    private static String findOptionValue(String[] args, String option) {
        for (int i = 0; i < args.length - 1; i++) {
            if (option.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static List<String> collectPositionalArgs(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (isOptionWithValue(token)) {
                i++;
                continue;
            }
            if (token.startsWith("--")) {
                continue;
            }
            out.add(token);
        }
        return out;
    }

    private static boolean isOptionWithValue(String token) {
        return "--db-url".equals(token)
                || "--db-user".equals(token)
                || "--db-password".equals(token)
                || "--star-table".equals(token)
                || "--surface-table".equals(token)
                || "--out-dir".equals(token);
    }

    private static String pick(String candidate, String fallback) {
        if (candidate == null) return fallback;
        String trimmed = candidate.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static List<RequestRow> parseRequestFile(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<RequestRow> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("[,;\\t ]+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": expected '<starSysIdx> <objectInternalId>'");
            }
            int sys = Integer.parseInt(parts[0]);
            int obj = Integer.parseInt(parts[1]);
            out.add(new RequestRow(sys, obj));
        }
        return out;
    }

    private static Path dumpTilesForDebug(Path outDir, StarSystemObjectRow row, PlanetConfig planet, List<Tile> tiles) throws Exception {
        String safeName = sanitizeFilePart(row.objectName);
        String fileName = "debug_tiles_StarSystem_" + row.starSysIdx + "_" + safeName + "_obj" + row.objectInternalId + ".tsv";
        Path out = outDir.resolve(fileName);
        List<String> lines = new ArrayList<>(tiles.size() + 4);

        lines.add("# Planet debug dump");
        lines.add("# generatedAtUtc=" + Instant.now());
        lines.add("# row.starSysIdx=" + row.starSysIdx);
        lines.add("# row.objectInternalId=" + row.objectInternalId);
        lines.add("# row.objectPlanetType=" + row.objectPlanetType);
        lines.add("# row.objectName=" + row.objectName);
        lines.add("# row.orbitMeanMotionPerDay=" + fmt(row.orbitMeanMotionPerDay));
        lines.add("# row.axialTiltDeg=" + fmt(row.axialTiltDeg));
        lines.add("# row.rotationPeriodHours=" + fmt(row.rotationPeriodHours));
        lines.add("# row.rotationSpeed=" + fmt(row.rotationSpeed));
        lines.add("# row.rotationPrograde=" + row.rotationPrograde);
        lines.add("# row.objectType=" + row.objectType);
        lines.add("# row.objectOrbitHost=" + row.objectOrbitHost);
        lines.add("# row.orbitSemimajorAxisAU=" + fmt(row.orbitSemimajorAxisAU));
        lines.add("# seed=" + computeSeed(row));
        lines.add("#");
        lines.add("# planet.tidalLocked=" + planet.tidalLocked);
        lines.add("# planet.hasAtmosphere=" + planet.hasAtmosphere);
        lines.add("# planet.atmosphereDensity=" + fmt(planet.atmosphereDensity));
        lines.add("# planet.gravity=" + fmt(planet.gravity));
        lines.add("# planet.radiusKm=" + fmt(planet.radiusKm));
        lines.add("# planet.axialTilt=" + fmt(planet.axialTilt));
        lines.add("# planet.rotationPeriodHours=" + fmt(planet.rotationPeriodHours));
        lines.add("# planet.rotationSpeed=" + fmt(planet.rotationSpeed));
        lines.add("# planet.rotationPrograde=" + planet.rotationPrograde);
        lines.add("# planet.meanTemperature=" + fmt(planet.meanTemperature));
        lines.add("# planet.meanTemperatureK=" + fmt(planet.meanTemperatureK));
        lines.add("# planet.minTemperatureK=" + fmt(planet.minTemperatureK));
        lines.add("# planet.maxTemperatureK=" + fmt(planet.maxTemperatureK));
        lines.add("# planet.equilibriumTemperatureK=" + fmt(planet.equilibriumTemperatureK));
        lines.add("# planet.greenhouseDeltaK=" + fmt(planet.greenhouseDeltaK));
        lines.add("# planet.waterCoverageOrdinal=" + planet.waterCoverageOrdinal);
        lines.add("# planet.waterGelKm=" + fmt(planet.waterGelKm));
        lines.add("# planet.fracIron=" + fmt(planet.fracIron));
        lines.add("# planet.fracRock=" + fmt(planet.fracRock));
        lines.add("# planet.fracIce=" + fmt(planet.fracIce));
        lines.add("# planet.o2Pct=" + fmt(planet.o2Pct));
        lines.add("# planet.hasLife=" + planet.hasLife);
        lines.add("# planet.lavaWorld=" + planet.lavaWorld);
        lines.add("# planet.volcanism=" + planet.volcanism);
        lines.add("# planet.methaneIceFrac=" + fmt(planet.methaneIceFrac));
        lines.add("# planet.ammoniaIceFrac=" + fmt(planet.ammoniaIceFrac));
        lines.add("# planet.organicsFrac=" + fmt(planet.organicsFrac));
        lines.add("# planet.heavyHydrocarbons=" + planet.heavyHydrocarbons);
        lines.add("# planet.lightHydrocarbons=" + planet.lightHydrocarbons);
        lines.add("# planet.moonCount=" + (planet.moonTideSources == null ? 0 : planet.moonTideSources.size()));
        lines.add("# planet.tidalOpenOceanRangeM=" + fmt(planet.tidalOpenOceanRangeM));
        lines.add("# planet.tidalCyclesPerDay=" + fmt(planet.tidalCyclesPerDay));
        lines.add("# planet.tidalDominantPeriodHours=" + fmt(planet.tidalDominantPeriodHours));
        if (planet.moonTideSources != null) {
            for (int i = 0; i < planet.moonTideSources.size(); i++) {
                PlanetConfig.MoonTideSource m = planet.moonTideSources.get(i);
                lines.add("# moon[" + i + "].id=" + m.objectInternalId
                        + " name=" + (m.objectName == null ? "" : m.objectName)
                        + " massEarth=" + fmt(m.massEarth)
                        + " axisAU=" + fmt(m.orbitSemimajorAxisAU)
                        + " inclDeg=" + fmt(m.orbitInclinationDeg)
                        + " meanMotionDegDay=" + fmt(m.meanMotionPerDay)
                        + " forcingRelEarthMoon=" + fmt(m.forcingRelativeEarthMoon));
            }
        }
        lines.addAll(ResourceStatsReport.buildDumpHeaderLines(tiles));
        lines.add("#");
        lines.add("id\tlat\tlon\tsurface\telev\tunderwater_elev\ttempC\ttempMin\ttempMax\tpressure\twindX_mps\twindY_mps\twindAvg_mps\twindMax_mps\t"
                + "precip\tevap\tprecip_kgm2day\tevap_kgm2day\tsoilMoist\tsoilStart\tsoilEnd\tsoilFromPrecip\tsoilFromEvap\tsoilFromDiff\tatmMoist\triverType\triverBase\triverTag\triverTo\triverOrder\triverFlow\triver_kgs\triver_tps\t"
                + "tempWarm\ttempCold\ttempMinInter\ttempMaxInter\ttempMinWarm\ttempMaxWarm\ttempMinCold\ttempMaxCold\twindXWarm_mps\twindYWarm_mps\twindXCold_mps\twindYCold_mps\twindWarm_mps\twindCold_mps\twindMaxWarm_mps\twindMaxCold_mps\t"
                + "precipWarm\tprecipCold\tprecipPhysInter_kgm2day\tprecipPhysWarm_kgm2day\tprecipPhysCold_kgm2day\tevapPhysInter_kgm2day\tevapPhysWarm_kgm2day\tevapPhysCold_kgm2day\trunoffPhysInter_kgm2day\trunoffPhysWarm_kgm2day\trunoffPhysCold_kgm2day\t"
                + "solarKwhDayInter\tsolarKwhDayWarm\tsolarKwhDayCold\t"
                + "tidalRangeM\ttidalPeriodH\ttidalCyclesPerDay\ttidalCoastAmpl\ttidalWaterBodyKm\t"
                + "biomeTempInter\tbiomeTempWarm\tbiomeTempCold\tbiomePrecipInter\tbiomePrecipWarm\tbiomePrecipCold\tbiomeEvapInter\tbiomeEvapWarm\tbiomeEvapCold\tbiomeMoistInter\tbiomeMoistWarm\tbiomeMoistCold\tbiomePreferredSeason\tbiomePreferredSeasonName\tbiomeWarmFromPosTilt\t"
                + "biomeRegime\tbiomeMods\tbiomeModMask\tbiomeTempRange\tbiomeAiAnn\tbiomeAiWarm\tbiomeAiCold\tbiomeMonsoon\tneighbors\triverFrom\tresources");

        for (Tile t : tiles) {
            String neighbors = joinTileIds(t.neighbors);
            String riverFrom = joinIntList(t.riverFrom);
            String resources = compactResources(t);

            lines.add(
                    t.id + "\t" +
                            fmt(t.lat) + "\t" +
                            fmt(t.lon) + "\t" +
                            (t.surfaceType != null ? t.surfaceType.name() : "UNKNOWN") + "\t" +
                            t.elevation + "\t" +
                            t.underwaterElevation + "\t" +
                            t.temperature + "\t" +
                            fmt(nan0(t.tempMin)) + "\t" +
                            fmt(nan0(t.tempMax)) + "\t" +
                            t.pressure + "\t" +
                            fmt(t.windX) + "\t" +
                            fmt(t.windY) + "\t" +
                            fmt(nan0(t.windAvg)) + "\t" +
                            fmt(nan0(t.windMax)) + "\t" +
                            fmt(nan0(t.precipAvg)) + "\t" +
                            fmt(nan0(t.evapAvg)) + "\t" +
                            fmt(nan0(t.precipKgM2Day)) + "\t" +
                            fmt(nan0(t.evapKgM2Day)) + "\t" +
                            fmt(nan0(t.moisture)) + "\t" +
                            fmt(nan0(t.soilStartDiag)) + "\t" +
                            fmt(nan0(t.soilEndDiag)) + "\t" +
                            fmt(nan0(t.soilFromPrecipDiag)) + "\t" +
                            fmt(nan0(t.soilFromEvapDiag)) + "\t" +
                            fmt(nan0(t.soilFromDiffDiag)) + "\t" +
                            fmt(nan0(t.atmMoist)) + "\t" +
                            t.riverType + "\t" +
                            (t.riverBaseType == null ? "NONE" : t.riverBaseType.name()) + "\t" +
                            (t.riverTag == null ? "" : t.riverTag) + "\t" +
                            t.riverTo + "\t" +
                            t.riverOrder + "\t" +
                            fmt(t.riverFlow) + "\t" +
                            fmt(t.riverDischargeKgS) + "\t" +
                            fmt(t.riverDischargeTps) + "\t" +
                            fmt(nan0(t.tempWarm)) + "\t" +
                            fmt(nan0(t.tempCold)) + "\t" +
                            fmt(nan0(t.tempMinInterseason)) + "\t" +
                            fmt(nan0(t.tempMaxInterseason)) + "\t" +
                            fmt(nan0(t.tempMinWarm)) + "\t" +
                            fmt(nan0(t.tempMaxWarm)) + "\t" +
                            fmt(nan0(t.tempMinCold)) + "\t" +
                            fmt(nan0(t.tempMaxCold)) + "\t" +
                            fmt(nan0(t.windXWarm)) + "\t" +
                            fmt(nan0(t.windYWarm)) + "\t" +
                            fmt(nan0(t.windXCold)) + "\t" +
                            fmt(nan0(t.windYCold)) + "\t" +
                            fmt(nan0(t.windWarm)) + "\t" +
                            fmt(nan0(t.windCold)) + "\t" +
                            fmt(nan0(t.windMaxWarm)) + "\t" +
                            fmt(nan0(t.windMaxCold)) + "\t" +
                            fmt(nan0(t.precipWarm)) + "\t" +
                            fmt(nan0(t.precipCold)) + "\t" +
                            fmt(nan0(t.precipKgM2DayInterseason)) + "\t" +
                            fmt(nan0(t.precipKgM2DayWarm)) + "\t" +
                            fmt(nan0(t.precipKgM2DayCold)) + "\t" +
                            fmt(nan0(t.evapKgM2DayInterseason)) + "\t" +
                            fmt(nan0(t.evapKgM2DayWarm)) + "\t" +
                            fmt(nan0(t.evapKgM2DayCold)) + "\t" +
                            fmt(nan0(t.surfaceRunoffKgM2DayInterseason)) + "\t" +
                            fmt(nan0(t.surfaceRunoffKgM2DayWarm)) + "\t" +
                            fmt(nan0(t.surfaceRunoffKgM2DayCold)) + "\t" +
                            fmt(nan0(t.solarKwhDayInter)) + "\t" +
                            fmt(nan0(t.solarKwhDayWarm)) + "\t" +
                            fmt(nan0(t.solarKwhDayCold)) + "\t" +
                            fmt(nan0(t.tidalRangeM)) + "\t" +
                            fmt(nan0(t.tidalPeriodHours)) + "\t" +
                            fmt(nan0(t.tidalCyclesPerDay)) + "\t" +
                            fmt(nan0(t.tidalCoastAmplification)) + "\t" +
                            fmt(nan0(t.tidalWaterBodyScaleKm)) + "\t" +
                            fmt(nan0(t.biomeTempInterseason)) + "\t" +
                            fmt(nan0(t.biomeTempWarm)) + "\t" +
                            fmt(nan0(t.biomeTempCold)) + "\t" +
                            fmt(nan0(t.biomePrecipInterseason)) + "\t" +
                            fmt(nan0(t.biomePrecipWarm)) + "\t" +
                            fmt(nan0(t.biomePrecipCold)) + "\t" +
                            fmt(nan0(t.biomeEvapInterseason)) + "\t" +
                            fmt(nan0(t.biomeEvapWarm)) + "\t" +
                            fmt(nan0(t.biomeEvapCold)) + "\t" +
                            fmt(nan0(t.biomeMoistureInterseason)) + "\t" +
                            fmt(nan0(t.biomeMoistureWarm)) + "\t" +
                            fmt(nan0(t.biomeMoistureCold)) + "\t" +
                            t.biomePreferredSeason + "\t" +
                            biomePreferredSeasonName(t.biomePreferredSeason) + "\t" +
                            t.biomeWarmFromPositiveTilt + "\t" +
                            (t.biomeRegime == null ? "UNKNOWN" : t.biomeRegime.name()) + "\t" +
                            BiomeModifier.toCsv(t.biomeModifierMask) + "\t" +
                            t.biomeModifierMask + "\t" +
                            fmt(nan0(t.biomeTempRange)) + "\t" +
                            fmt(nan0(t.biomeAiAnn)) + "\t" +
                            fmt(nan0(t.biomeAiWarm)) + "\t" +
                            fmt(nan0(t.biomeAiCold)) + "\t" +
                            fmt(nan0(t.biomeMonsoon)) + "\t" +
                            neighbors + "\t" +
                            riverFrom + "\t" +
                            resources
            );
        }

        rotateDumpHistory(out);
        Files.write(
                out,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        return out;
    }

    private static void rotateDumpHistory(Path current) throws Exception {
        String name = current.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext = (dot >= 0) ? name.substring(dot) : "";

        Path prev1 = current.resolveSibling(base + "_prev1" + ext);
        Path prev2 = current.resolveSibling(base + "_prev2" + ext);

        Files.deleteIfExists(prev2);
        if (Files.exists(prev1)) {
            Files.move(prev1, prev2, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(current)) {
            Files.move(current, prev1, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String joinTileIds(List<Tile> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            Tile t = list.get(i);
            sb.append(t != null ? t.id : -1);
        }
        return sb.toString();
    }

    private static String joinIntList(List<Integer> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            Integer v = list.get(i);
            sb.append(v != null ? v : -1);
        }
        return sb.toString();
    }

    private static String compactResources(Tile t) {
        if (t.resources == null || t.resources.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.resources.size(); i++) {
            ResourcePresence r = t.resources.get(i);
            if (i > 0) sb.append(';');
            sb.append(r.type.id).append(':')
                    .append(r.layer.ordinal()).append(':')
                    .append(r.quality).append(':')
                    .append(r.saturation).append(':')
                    .append(r.amount);
            if (r.type == ResourceType.TIDAL_PWR) {
                sb.append(':').append(fmt(r.logTonnes)).append(':').append(fmt(r.tonnes));
            }
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String sanitizeFilePart(String s) {
        if (s == null || s.isBlank()) return "unnamed";
        String cleaned = s.trim()
                .replaceAll("[^a-zA-Z0-9._-]+", "_")
                .replaceAll("_+", "_");
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80);
        }
        if (cleaned.isBlank()) return "unnamed";
        return cleaned;
    }

    private static double nan0(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    private static String biomePreferredSeasonName(int id) {
        return switch (id) {
            case 0 -> "INTERSEASON";
            case 1 -> "SUMMER";
            case 2 -> "WINTER";
            default -> "UNKNOWN";
        };
    }

    private static long computeSeed(StarSystemObjectRow row) {
        return row.starSysIdx * 1_000_000L + row.objectInternalId;
    }

    private static synchronized void appendBatchLog(Path file, String line) {
        String msg = Instant.now() + " " + line + System.lineSeparator();
        try {
            Files.writeString(file, msg, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // logging must not break generation flow
        }
    }

    private static String sanitizeLogMessage(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static final class RequestRow {
        final int starSysIdx;
        final int objectInternalId;

        RequestRow(int starSysIdx, int objectInternalId) {
            this.starSysIdx = starSysIdx;
            this.objectInternalId = objectInternalId;
        }
    }
}
