package org.planet.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import javafx.concurrent.Task;
import javafx.scene.paint.Color;
import org.planet.core.generation.GenerationPipeline;
import org.planet.core.io.CsvTileLoader;
import org.planet.core.io.PlanetSurfaceSerializer;
import org.planet.core.io.TileSetSelector;
import org.planet.core.model.BiomeModifier;
import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.config.ClimateModelMode;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;
import org.planet.ui.EditorController;
import org.planet.ui.MapRenderer;
import org.planet.core.generation.ResourceType;
import org.planet.core.generation.ResourceStatsReport;
import org.planet.core.generation.StageProfile;
import org.planet.core.generation.ConsoleStageListener;
import org.planet.core.generation.WorldClassifier;
import org.planet.core.generation.WorldType;
import org.planet.core.generation.PlanetTuning;
import org.planet.core.db.DataSourceFactory;
import org.planet.core.db.DbConfig;
import org.planet.core.db.MoonTideResolver;
import org.planet.core.db.PlanetConfigMapper;
import org.planet.core.db.PlanetSurfaceRepository;
import org.planet.core.db.StarSystemRepository;
import org.planet.core.db.dto.StarSystemObjectRow;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

public class Main extends Application {

    private static final Path DUMP_REQUEST_FILE = Paths.get(
            "/home/vladimirs/PlanetSurfaceGenerator/planet-generator/dump_request.txt"
    );
    private static final Path BATCH_LOG_FILE = Paths.get(
            "/home/vladimirs/PlanetSurfaceGenerator/planet-generator/batch_generation.log"
    );
    private static final String PREF_LAST_SYSTEM = "last.system.idx";
    private static final String PREF_CLIMATE_MODE = "climate.mode";
    private static final String PREF_CLIMATE_PARALLEL = "climate.parallel";
    private static final String PREF_STAR_TABLE = "db.table.starsystems";
    private static final String PREF_SURFACE_TABLE = "db.table.surfaces";
    private static final int PDATA_FIELDS_COUNT = 38;
    private static final String[] PDATA_FIELD_NAMES = {
            "PData[0] Kind",
            "PData[1] mE (Earth masses)",
            "PData[2] Field2",
            "PData[3] Field3",
            "PData[4] rE (Earth radii)",
            "PData[5] sG (surface gravity, g)",
            "PData[6] fracIron",
            "PData[7] fracRock",
            "PData[8] fracIce",
            "PData[9] Field9",
            "PData[10] pBar (atmosphere density)",
            "PData[11] atmType",
            "PData[12] Field12",
            "PData[13] Field13",
            "PData[14] Field14",
            "PData[15] Field15",
            "PData[16] teqK",
            "PData[17] greenhouseDeltaK",
            "PData[18] tMeanK",
            "PData[19] tMinK",
            "PData[20] tMaxK",
            "PData[21] tidalLock",
            "PData[22] tidalHeat",
            "PData[23] Field23",
            "PData[24] biosphereProvenance",
            "PData[25] biosphereSurfaceStatus",
            "PData[26] biosphereMicrobialStatus",
            "PData[27] biosphereSubsurfaceStatus",
            "PData[28] heavyHydrocarbons",
            "PData[29] lightHydrocarbons",
            "PData[30] Field30",
            "PData[31] Field31",
            "PData[32] waterCoverageOrdinal",
            "PData[33] waterGelKm",
            "PData[34] o2Pct",
            "PData[35] Field35",
            "PData[36] Field36",
            "PData[37] PRes"
    };
    private final Preferences prefs = Preferences.userNodeForPackage(Main.class);

    private static final class PlanetSelection {
        final StarSystemObjectRow row;
        final ClimateModelMode climateMode;
        final String starSystemsTable;
        final String planetSurfacesTable;

        PlanetSelection(StarSystemObjectRow row,
                        ClimateModelMode climateMode,
                        String starSystemsTable,
                        String planetSurfacesTable) {
            this.row = row;
            this.climateMode = climateMode;
            this.starSystemsTable = starSystemsTable;
            this.planetSurfacesTable = planetSurfacesTable;
        }
    }

    private static final class GenerationResult {
        final PlanetConfig planet;
        final List<Tile> tiles;

        GenerationResult(PlanetConfig planet, List<Tile> tiles) {
            this.planet = planet;
            this.tiles = tiles;
        }
    }

    @Override
    public void start(Stage stage) {
        // 0) База данных (пока хардкод для отладки)
        DbConfig cfg = new DbConfig();
        cfg.jdbcUrl = "jdbc:mysql://localhost:3306/EXOLOG";
        cfg.user = "ghost_reg";
        cfg.password = "REDACTED_DB_PASSWORD";
        cfg.applyOverridesFromSystem();
        DataSource ds = DataSourceFactory.create(cfg);

        // 1) Выбор системы/планеты через UI
        PlanetSelection selected = promptPlanetSelection(stage, ds, cfg.starSystemsTable, cfg.planetSurfacesTable);
        if (selected == null) {
            stage.close();
            return;
        }
        StarSystemRepository repo = new StarSystemRepository(ds, selected.starSystemsTable);
        showPlanet(stage, ds, repo, selected.row, selected.climateMode, selected.starSystemsTable, selected.planetSurfacesTable);
    }

    private void showPlanet(Stage stage,
                            DataSource ds,
                            StarSystemRepository repo,
                            StarSystemObjectRow selected,
                            ClimateModelMode climateMode,
                            String starSystemsTable,
                            String planetSurfacesTable) {
        StarSystemObjectRow row;
        try {
            row = repo.loadObjectRow(selected.starSysIdx, selected.objectInternalId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load planet row", e);
        }

        GenerationResult generated = generatePlanetPreview(repo, row, climateMode);
        PlanetConfig planet = generated.planet;
        List<Tile> tiles = generated.tiles;

        // 7) Быстрый лог (чтобы проверить, что wind/moisture реально считаются)
        tiles.stream()
                .limit(60)
                .forEach(t ->
                        System.out.println(
                                "T=" + t.temperature +
                                        " P=" + t.pressure +
                                        " lat=" + t.lat +
                                        " windX=" + t.windX +
                                        " windY=" + t.windY +
                                        " moisture=" + t.moisture +
                                        " elevation=" + t.elevation
                        )
                );

        // 8) UI
        Canvas canvas = new Canvas(1200, 600);
        final PlanetConfig[] planetRef = {planet};

        MapRenderer renderer = new MapRenderer(canvas);
        EditorController editor = new EditorController(canvas, tiles);
        editor.enable();

        MapRenderer.DisplayMode[] modes = {
                MapRenderer.DisplayMode.SURFACE,
                MapRenderer.DisplayMode.ELEVATION,
                MapRenderer.DisplayMode.TEMP,
                MapRenderer.DisplayMode.MOISTURE,
                MapRenderer.DisplayMode.WIND,
                MapRenderer.DisplayMode.WATER_RIVERS,
                MapRenderer.DisplayMode.RESOURCES,
                MapRenderer.DisplayMode.FERTILITY
        };

        ToggleGroup modeGroup = new ToggleGroup();
        HBox modeBar = new HBox(6);
        modeBar.setPadding(new Insets(6));

        ChoiceBox<MapRenderer.SeasonView> seasonBox = new ChoiceBox<>();
        seasonBox.getItems().addAll(MapRenderer.SeasonView.ANNUAL, MapRenderer.SeasonView.SUMMER, MapRenderer.SeasonView.WINTER);
        seasonBox.setValue(MapRenderer.SeasonView.ANNUAL);

        ComboBox<ResourceType> resourceBox = new ComboBox<>();
        resourceBox.getItems().addAll(ResourceType.values());
        resourceBox.setValue(ResourceType.Fe_HEM);

        java.util.Set<ResourceType> presentResources = new java.util.HashSet<>();
        rebuildPresentResources(tiles, presentResources);
        resourceBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ResourceType item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTextFill(Color.BLACK);
                } else {
                    setText(item.name());
                    setTextFill(presentResources.contains(item) ? Color.GREEN : Color.GRAY);
                }
            }
        });
        resourceBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ResourceType item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTextFill(Color.BLACK);
                } else {
                    setText(item.name());
                    setTextFill(presentResources.contains(item) ? Color.GREEN : Color.GRAY);
                }
            }
        });

        ChoiceBox<MapRenderer.ResourceLayerView> layerBox = new ChoiceBox<>();
        layerBox.getItems().addAll(MapRenderer.ResourceLayerView.ALL, MapRenderer.ResourceLayerView.SURFACE,
                MapRenderer.ResourceLayerView.DEEP, MapRenderer.ResourceLayerView.VERY_DEEP);
        layerBox.setValue(MapRenderer.ResourceLayerView.ALL);

        final MapRenderer.DisplayMode[] currentMode = {MapRenderer.DisplayMode.SURFACE};

        for (MapRenderer.DisplayMode m : modes) {
            ToggleButton btn = new ToggleButton(m.name());
            btn.setUserData(m);
            btn.setToggleGroup(modeGroup);
            if (m == MapRenderer.DisplayMode.SURFACE) btn.setSelected(true);
            modeBar.getChildren().add(btn);
        }

        resourceBox.setDisable(true);
        layerBox.setDisable(true);

        Button backButton = new Button("Back to Select");
        backButton.setOnAction(e -> {
            PlanetSelection next = promptPlanetSelection(stage, ds, starSystemsTable, planetSurfacesTable);
            if (next != null) {
                StarSystemRepository nextRepo = new StarSystemRepository(ds, next.starSystemsTable);
                showPlanet(stage, ds, nextRepo, next.row, next.climateMode, next.starSystemsTable, next.planetSurfacesTable);
            }
        });

        Button dumpButton = new Button("Dump Tiles");
        dumpButton.setOnAction(e -> {
            try {
                Path dumpPath = dumpTilesForDebug(row, planetRef[0], tiles);
                System.out.println("[DUMP] Dumped: " + dumpPath);
            } catch (Exception ex) {
                System.err.println("[DUMP] Dump failed: " + ex.getMessage());
                ex.printStackTrace(System.err);
            }
        });
        Button planetEditButton = new Button("Planet Edit");

        HBox controlBar = new HBox(10, backButton, dumpButton, planetEditButton, modeBar, new Label("Season:"), seasonBox, new Label("Resource:"), resourceBox,
                new Label("Layer:"), layerBox);
        controlBar.setPadding(new Insets(6));

        BorderPane root = new BorderPane(canvas);
        VBox legend = renderer.buildLegend(currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue());
        root.setRight(legend);

        renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue()));
        canvas.setOnMouseClicked(e -> renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue())));

        Tooltip tip = new Tooltip();
        tip.setShowDelay(javafx.util.Duration.millis(80));
        tip.setShowDuration(javafx.util.Duration.seconds(15));
        tip.setHideDelay(javafx.util.Duration.seconds(3));
        Tooltip.install(canvas, tip);
        canvas.setOnMouseMoved(e -> {
            Tile t = renderer.pickNearestTile(tiles, e.getX(), e.getY());
            tip.setText(renderer.formatTooltip(t, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue()));
        });
        canvas.setOnMouseExited(e -> { });

        Runnable updateLegend = () -> {
            VBox newLegend = renderer.buildLegend(currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue());
            root.setRight(newLegend);
        };

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                currentMode[0] = (MapRenderer.DisplayMode) newVal.getUserData();
                boolean res = currentMode[0] == MapRenderer.DisplayMode.RESOURCES;
                resourceBox.setDisable(!res);
                layerBox.setDisable(!res);
                renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                        planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue()));
                updateLegend.run();
            }
        });

        seasonBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                    planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue()));
            updateLegend.run();
        });
        resourceBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                    planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue()));
            updateLegend.run();
        });
        layerBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                    planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue()));
            updateLegend.run();
        });

        root.setTop(controlBar);
        VBox bottom = new VBox(4, buildStatsBar(tiles), buildSaveBar(ds, row, tiles, planetRef[0], computeSeed(row), starSystemsTable, planetSurfacesTable));
        bottom.setPadding(new Insets(4, 8, 8, 8));
        root.setBottom(bottom);

        Runnable rerenderMap = () -> renderer.render(tiles, currentMode[0], seasonBox.getValue(), resourceBox.getValue(), layerBox.getValue(),
                planetRef[0].tidalLocked, terminatorLatitudeForView(planetRef[0], seasonBox.getValue()));
        Runnable refreshBottom = () -> {
            VBox newBottom = new VBox(4, buildStatsBar(tiles), buildSaveBar(ds, row, tiles, planetRef[0], computeSeed(row), starSystemsTable, planetSurfacesTable));
            newBottom.setPadding(new Insets(4, 8, 8, 8));
            root.setBottom(newBottom);
        };

        planetEditButton.setOnAction(e -> openPlanetEditorWindow(
                stage, repo, row, climateMode, planetRef, tiles, presentResources,
                resourceBox, updateLegend, rerenderMap, refreshBottom
        ));
        stage.setScene(new Scene(root));
        stage.setTitle("Planet Surface Editor");
        stage.show();
    }

    private GenerationResult generatePlanetPreview(StarSystemRepository repo, StarSystemObjectRow row, ClimateModelMode climateMode) {
        PlanetConfig planet = PlanetConfigMapper.fromDescription(row);
        MoonTideResolver.populateMoonTideSources(planet, row, repo);

        String tilesPath = TileSetSelector.pickTilesPath(planet.radiusKm);
        List<Tile> tiles = CsvTileLoader.load(tilesPath);

        WorldType worldType = WorldClassifier.classify(planet);
        int plateCount = PlanetTuning.plateCount(planet, worldType);
        if (planet.lavaWorld) {
            for (Tile t : tiles) {
                t.volcanism = 100;
                t.temperature = 800;
            }
        }

        long seed = computeSeed(row);
        GeneratorSettings settings = new GeneratorSettings(seed);
        settings.seed = seed;
        settings.climateModelMode = (climateMode == null) ? ClimateModelMode.ENHANCED : climateMode;
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
        pipeline.run(tiles, planet, settings, plateCount);
        return new GenerationResult(planet, tiles);
    }

    private void openPlanetEditorWindow(Stage owner,
                                        StarSystemRepository repo,
                                        StarSystemObjectRow row,
                                        ClimateModelMode climateMode,
                                        PlanetConfig[] planetRef,
                                        List<Tile> tiles,
                                        java.util.Set<ResourceType> presentResources,
                                        ComboBox<ResourceType> resourceBox,
                                        Runnable updateLegend,
                                        Runnable rerenderMap,
                                        Runnable refreshBottom) {
        Stage editStage = new Stage();
        editStage.initOwner(owner);
        editStage.setTitle("Planet Edit | StarSystem_" + row.starSysIdx + " obj" + row.objectInternalId);

        String[] original = splitObjectDescription(row.objectDescription, PDATA_FIELDS_COUNT);
        List<TextField> fields = new ArrayList<>(PDATA_FIELDS_COUNT);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        for (int i = 0; i < PDATA_FIELDS_COUNT; i++) {
            Label idx = new Label(String.format("%02d", i));
            Label name = new Label(PDATA_FIELD_NAMES[i]);
            TextField tf = new TextField(original[i]);
            tf.setPrefColumnCount(26);
            fields.add(tf);
            grid.add(idx, 0, i);
            grid.add(name, 1, i);
            grid.add(tf, 2, i);
        }

        Label status = new Label("Edit fields, then Regenerate Preview or Save To DB.");
        status.setWrapText(true);

        Button regenerateButton = new Button("Regenerate Preview");
        Button saveDbButton = new Button("Save To DB");
        Button resetButton = new Button("Reset");

        regenerateButton.setOnAction(e -> {
            try {
                String newDesc = joinObjectDescription(fields, PDATA_FIELDS_COUNT);
                StarSystemObjectRow edited = cloneRowWithDescription(row, newDesc);
                GenerationResult result = generatePlanetPreview(repo, edited, climateMode);

                row.objectDescription = newDesc;
                planetRef[0] = result.planet;
                tiles.clear();
                tiles.addAll(result.tiles);
                rebuildPresentResources(tiles, presentResources);
                resourceBox.getItems().setAll(ResourceType.values());
                if (resourceBox.getValue() == null) resourceBox.setValue(ResourceType.Fe_HEM);
                rerenderMap.run();
                updateLegend.run();
                refreshBottom.run();
                status.setText("Preview regenerated from edited parameters.");
            } catch (Exception ex) {
                status.setText("Regenerate failed: " + ex.getMessage());
            }
        });

        saveDbButton.setOnAction(e -> {
            try {
                String newDesc = joinObjectDescription(fields, PDATA_FIELDS_COUNT);
                int updated = repo.updateObjectDescription(row.starSysIdx, row.objectInternalId, newDesc);
                row.objectDescription = newDesc;
                status.setText("Saved to DB. Rows updated: " + updated);
            } catch (Exception ex) {
                status.setText("Save failed: " + ex.getMessage());
            }
        });

        resetButton.setOnAction(e -> {
            String[] cur = splitObjectDescription(row.objectDescription, PDATA_FIELDS_COUNT);
            for (int i = 0; i < PDATA_FIELDS_COUNT; i++) {
                fields.get(i).setText(cur[i]);
            }
            status.setText("Fields reset from current in-memory object description.");
        });

        HBox buttons = new HBox(10, regenerateButton, saveDbButton, resetButton);
        buttons.setPadding(new Insets(8, 10, 8, 10));
        VBox content = new VBox(8, buttons, status, grid);
        content.setPadding(new Insets(6));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        Scene scene = new Scene(scroll, 1180, 760);
        editStage.setScene(scene);
        editStage.show();
    }

    private StarSystemObjectRow cloneRowWithDescription(StarSystemObjectRow src, String objectDescription) {
        StarSystemObjectRow r = new StarSystemObjectRow();
        r.starSysIdx = src.starSysIdx;
        r.objectInternalId = src.objectInternalId;
        r.objectType = src.objectType;
        r.objectPlanetType = src.objectPlanetType;
        r.objectOrbitHost = src.objectOrbitHost;
        r.orbitSemimajorAxisAU = src.orbitSemimajorAxisAU;
        r.orbitInclinationDeg = src.orbitInclinationDeg;
        r.objectMassEarth = src.objectMassEarth;
        r.objectName = src.objectName;
        r.objectDescription = objectDescription;
        r.orbitMeanMotionPerDay = src.orbitMeanMotionPerDay;
        r.axialTiltDeg = src.axialTiltDeg;
        r.rotationPeriodHours = src.rotationPeriodHours;
        r.rotationSpeed = src.rotationSpeed;
        r.rotationPrograde = src.rotationPrograde;
        return r;
    }

    private String[] splitObjectDescription(String desc, int expectedCount) {
        String[] out = new String[expectedCount];
        Arrays.fill(out, "");
        if (desc == null || desc.isBlank()) return out;
        String s = desc.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        String[] p = s.split(",", -1);
        for (int i = 0; i < Math.min(expectedCount, p.length); i++) {
            out[i] = p[i].trim().replace("\"", "");
        }
        return out;
    }

    private String joinObjectDescription(List<TextField> fields, int expectedCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < expectedCount; i++) {
            if (i > 0) sb.append(',');
            String v = (i < fields.size() && fields.get(i) != null) ? fields.get(i).getText() : "";
            sb.append(v == null ? "" : v.trim());
        }
        return sb.toString();
    }

    private void rebuildPresentResources(List<Tile> tiles, java.util.Set<ResourceType> out) {
        out.clear();
        for (Tile t : tiles) {
            if (t.resources == null) continue;
            for (org.planet.core.generation.ResourcePresence rp : t.resources) {
                out.add(rp.type);
            }
        }
    }

    private PlanetSelection promptPlanetSelection(Stage owner,
                                                  DataSource ds,
                                                  String defaultStarSystemsTable,
                                                  String defaultPlanetSurfacesTable) {
        Dialog<PlanetSelection> dialog = new Dialog<>();
        dialog.setTitle("Select Planet");
        // Не привязываем к owner, чтобы не падать, когда у stage ещё нет Scene

        int rememberedSystem = prefs.getInt(PREF_LAST_SYSTEM, 1);
        String rememberedMode = prefs.get(PREF_CLIMATE_MODE, ClimateModelMode.ENHANCED.name());
        boolean rememberedParallel = prefs.getBoolean(PREF_CLIMATE_PARALLEL, true);
        ClimateModelMode defaultMode;
        try {
            defaultMode = ClimateModelMode.valueOf(rememberedMode);
        } catch (IllegalArgumentException ex) {
            defaultMode = ClimateModelMode.ENHANCED;
        }
        String rememberedStarTable = prefs.get(PREF_STAR_TABLE, defaultStarSystemsTable);
        String rememberedSurfaceTable = prefs.get(PREF_SURFACE_TABLE, defaultPlanetSurfacesTable);

        TextField systemField = new TextField(String.valueOf(rememberedSystem));
        systemField.setPromptText("Star system index (1..50001)");
        TextField starTableField = new TextField(rememberedStarTable);
        starTableField.setPromptText("Star systems table (e.g. StarSystems)");
        TextField surfaceTableField = new TextField(rememberedSurfaceTable);
        surfaceTableField.setPromptText("Surface table (e.g. PlanetsSurfaces2)");
        ChoiceBox<ClimateModelMode> climateModeBox = new ChoiceBox<>();
        climateModeBox.getItems().setAll(ClimateModelMode.ENHANCED, ClimateModelMode.PHYSICAL);
        climateModeBox.setValue(defaultMode);
        CheckBox parallelBox = new CheckBox("Climate Parallel");
        parallelBox.setSelected(rememberedParallel);
        System.setProperty("planet.climate.parallel", Boolean.toString(rememberedParallel));
        parallelBox.setOnAction(e -> {
            boolean on = parallelBox.isSelected();
            System.setProperty("planet.climate.parallel", Boolean.toString(on));
            prefs.putBoolean(PREF_CLIMATE_PARALLEL, on);
        });

        Button loadButton = new Button("Load");
        Button selectButton = new Button("Select");
        Button cancelButton = new Button("Cancel");
        Label statusLabel = new Label("Enter system index and press Load.");

        ListView<StarSystemObjectRow> listView = new ListView<>();
        TextArea detailsArea = new TextArea();
        detailsArea.setEditable(false);
        detailsArea.setWrapText(true);
        detailsArea.setPrefRowCount(8);
        detailsArea.setText("Select a planet/moon to see details.");
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(StarSystemObjectRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String type = (item.objectPlanetType == 4) ? "Moon" : "Planet";
                    setText(item.objectInternalId + " | " + type + " | " + item.objectName);
                }
            }
        });

        loadButton.setOnAction(e -> {
            String txt = systemField.getText().trim();
            int idx;
            try {
                idx = Integer.parseInt(txt);
            } catch (NumberFormatException ex) {
                statusLabel.setText("Invalid system index.");
                return;
            }
            try {
                String starTable = tableOrDefault(starTableField.getText(), defaultStarSystemsTable);
                String surfaceTable = tableOrDefault(surfaceTableField.getText(), defaultPlanetSurfacesTable);
                StarSystemRepository tableRepo = new StarSystemRepository(ds, starTable);
                StarSystemObjectRow previouslySelected = listView.getSelectionModel().getSelectedItem();
                Integer prevId = previouslySelected == null ? null : previouslySelected.objectInternalId;
                List<StarSystemObjectRow> candidates = tableRepo.listCandidates(idx);
                listView.getItems().setAll(candidates);
                StarSystemObjectRow restored = null;
                if (prevId != null) {
                    for (StarSystemObjectRow c : candidates) {
                        if (c != null && c.objectInternalId == prevId) {
                            restored = c;
                            break;
                        }
                    }
                }
                if (restored != null) {
                    listView.getSelectionModel().select(restored);
                    statusLabel.setText("Reloaded " + candidates.size() + " objects from " + starTable + " for StarSystem_" + idx
                            + " | Selection kept: obj " + restored.objectInternalId
                            + " | Save table: " + surfaceTable);
                } else if (candidates.size() == 1) {
                    listView.getSelectionModel().select(0);
                    statusLabel.setText("Loaded " + candidates.size() + " object from " + starTable + " for StarSystem_" + idx
                            + " | Auto-selected | Save table: " + surfaceTable);
                } else {
                    detailsArea.setText("Select a planet/moon to see details.");
                    statusLabel.setText("Loaded " + candidates.size() + " objects from " + starTable + " for StarSystem_" + idx
                            + " | Save table: " + surfaceTable);
                }
                prefs.putInt(PREF_LAST_SYSTEM, idx);
                prefs.put(PREF_STAR_TABLE, starTable);
                prefs.put(PREF_SURFACE_TABLE, surfaceTable);
            } catch (Exception ex) {
                statusLabel.setText("DB error: " + ex.getMessage());
            }
        });

        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                detailsArea.setText("Select a planet/moon to see details.");
                return;
            }
            try {
                String starTable = tableOrDefault(starTableField.getText(), defaultStarSystemsTable);
                StarSystemRepository tableRepo = new StarSystemRepository(ds, starTable);
                StarSystemObjectRow full = tableRepo.loadObjectRow(newVal.starSysIdx, newVal.objectInternalId);
                PlanetConfig pc = PlanetConfigMapper.fromDescription(full);
                MoonTideResolver.populateMoonTideSources(pc, full, tableRepo);
                String tilesPath = TileSetSelector.pickTilesPath(pc.radiusKm);
                WorldType wt = WorldClassifier.classify(pc);
                String type = (full.objectPlanetType == 4) ? "Moon" : "Planet";
                detailsArea.setText(
                        "Name: " + full.objectName + "\n" +
                        "Type: " + type + "\n" +
                        "Mean Temp (C): " + String.format("%.1f", pc.meanTemperature) + "\n" +
                        "Gravity (g): " + String.format("%.2f", pc.gravity) + "\n" +
                        "Atmosphere (bar): " + String.format("%.2f", pc.atmosphereDensity) + "\n" +
                        "Life: " + pc.hasLife + "\n" +
                        "O2 (%): " + String.format("%.2f", pc.o2Pct) + "\n" +
                        "Tidal Locked: " + pc.tidalLocked + "\n" +
                        "Moons (tide): " + (pc.moonTideSources == null ? 0 : pc.moonTideSources.size()) + "\n" +
                        "Volcanism: " + pc.volcanism + "\n" +
                        "Lava World: " + pc.lavaWorld + "\n" +
                        "World Type: " + wt + "\n" +
                        "Tiles File: " + tilesPath
                );
            } catch (Exception ex) {
                detailsArea.setText("Failed to load details: " + ex.getMessage());
            }
        });

        selectButton.setOnAction(e -> {
            StarSystemObjectRow row = listView.getSelectionModel().getSelectedItem();
            if (row == null) {
                statusLabel.setText("Select a planet/moon first.");
                return;
            }
            ClimateModelMode mode = climateModeBox.getValue() == null ? ClimateModelMode.ENHANCED : climateModeBox.getValue();
            prefs.put(PREF_CLIMATE_MODE, mode.name());
            prefs.putBoolean(PREF_CLIMATE_PARALLEL, parallelBox.isSelected());
            String starTable = tableOrDefault(starTableField.getText(), defaultStarSystemsTable);
            String surfaceTable = tableOrDefault(surfaceTableField.getText(), defaultPlanetSurfacesTable);
            prefs.put(PREF_STAR_TABLE, starTable);
            prefs.put(PREF_SURFACE_TABLE, surfaceTable);
            String txt = systemField.getText().trim();
            try {
                prefs.putInt(PREF_LAST_SYSTEM, Integer.parseInt(txt));
            } catch (NumberFormatException ignored) {
                // keep previous value
            }
            dialog.setResult(new PlanetSelection(row, mode, starTable, surfaceTable));
            dialog.close();
        });

        cancelButton.setOnAction(e -> {
            dialog.setResult(null);
            dialog.close();
        });

        HBox actions = new HBox(8, loadButton, selectButton, cancelButton);

        VBox box = new VBox(8,
                new Label("Star system:"),
                systemField,
                new Label("DB source table (objects):"),
                starTableField,
                new Label("DB destination table (surfaces):"),
                surfaceTableField,
                new Label("Climate model:"),
                climateModeBox,
                parallelBox,
                actions,
                statusLabel,
                listView,
                new Label("Details:"),
                detailsArea
        );
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);

        return dialog.showAndWait().orElse(null);
    }

    private String tableOrDefault(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private long computeSeed(StarSystemObjectRow row) {
        return row.starSysIdx * 1_000_000L + row.objectInternalId;
    }

    private double terminatorLatitudeForView(PlanetConfig planet, MapRenderer.SeasonView season) {
        double tilt = planet.axialTilt;
        if (Double.isNaN(tilt)) tilt = 0.0;
        if (tilt > 90.0) tilt = 90.0;
        if (tilt < -90.0) tilt = -90.0;

        return switch (season) {
            case SUMMER -> tilt;
            case WINTER -> -tilt;
            case ANNUAL -> 0.0;
        };
    }

    private HBox buildStatsBar(List<Tile> tiles) {
        long riverTiles = tiles.stream().filter(t -> t.riverType > 0).count();
        int total = tiles.size();
        String statsText = "Tiles: " + total + " | River tiles: " + riverTiles;
        Label stats = new Label(statsText);
        HBox bar = new HBox(10, new Label("Stats:"), stats);
        bar.setPadding(new Insets(4, 0, 0, 0));
        return bar;
    }

    private Path dumpTilesForDebug(StarSystemObjectRow row, PlanetConfig planet, List<Tile> tiles) throws Exception {
        String safeName = sanitizeFilePart(row.objectName);
        String fileName = "debug_tiles_StarSystem_" + row.starSysIdx + "_" + safeName + "_obj" + row.objectInternalId + ".tsv";
        Path out = Paths.get("/home/vladimirs/PlanetSurfaceGenerator/planet-generator", fileName);
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

    private void rotateDumpHistory(Path current) throws Exception {
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

    private String joinTileIds(List<Tile> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            Tile t = list.get(i);
            sb.append(t != null ? t.id : -1);
        }
        return sb.toString();
    }

    private String joinIntList(List<Integer> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            Integer v = list.get(i);
            sb.append(v != null ? v : -1);
        }
        return sb.toString();
    }

    private String compactResources(Tile t) {
        if (t.resources == null || t.resources.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.resources.size(); i++) {
            org.planet.core.generation.ResourcePresence r = t.resources.get(i);
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

    private String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private String sanitizeFilePart(String s) {
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

    private double nan0(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    private String biomePreferredSeasonName(int id) {
        return switch (id) {
            case 0 -> "INTERSEASON";
            case 1 -> "SUMMER";
            case 2 -> "WINTER";
            default -> "UNKNOWN";
        };
    }

    private HBox buildSaveBar(DataSource ds,
                              StarSystemObjectRow row,
                              List<Tile> tiles,
                              PlanetConfig planet,
                              long seed,
                              String starSystemsTable,
                              String planetSurfacesTable) {
        PlanetSurfaceRepository surfaceRepo = new PlanetSurfaceRepository(ds, planetSurfacesTable);
        StarSystemRepository repo = new StarSystemRepository(ds, starSystemsTable);

        Button saveButton = new Button("Save To DB");
        Button batchButton = new Button("Batch Save");
        Button dumpBatchButton = new Button("Batch Dump");
        TextField fromField = new TextField("2");
        TextField toField = new TextField("50001");
        fromField.setPrefWidth(80);
        toField.setPrefWidth(80);
        Label saveStatus = new Label("");

        saveButton.setOnAction(e -> {
            try {
                appendBatchLog(BATCH_LOG_FILE, "[SINGLE_SAVE_START] sys=" + row.starSysIdx
                        + " obj=" + row.objectInternalId
                        + " name=" + sanitizeLogMessage(row.objectName));
                System.out.println("[SINGLE_SAVE_START] sys=" + row.starSysIdx
                        + " obj=" + row.objectInternalId
                        + " name=" + row.objectName);

                String hexJson = PlanetSurfaceSerializer.toJson(tiles, planet);
                PlanetSurfaceRepository.UpsertReceipt receipt = surfaceRepo.upsertSurface(
                        row.starSysIdx,
                        row.objectInternalId,
                        row.objectName,
                        seed,
                        hexJson
                );
                saveStatus.setText("Saved. jsonBytes=" + receipt.charLength + " jsonKeys=" + receipt.jsonLength);
                appendBatchLog(BATCH_LOG_FILE, "[SINGLE_SAVE_OK] sys=" + row.starSysIdx
                        + " obj=" + row.objectInternalId
                        + " rows=" + receipt.rowsAffected
                        + " bytes=" + receipt.charLength
                        + " jsonKeys=" + receipt.jsonLength);
                System.out.println("[SINGLE_SAVE_OK] sys=" + row.starSysIdx
                        + " obj=" + row.objectInternalId
                        + " rows=" + receipt.rowsAffected
                        + " bytes=" + receipt.charLength
                        + " jsonKeys=" + receipt.jsonLength);
            } catch (Exception ex) {
                saveStatus.setText("Save failed: " + ex.getMessage());
                appendBatchLog(BATCH_LOG_FILE, "[SINGLE_SAVE_FAIL] sys=" + row.starSysIdx
                        + " obj=" + row.objectInternalId
                        + " msg=" + sanitizeLogMessage(ex.getMessage()));
                System.out.println("[SINGLE_SAVE_FAIL] sys=" + row.starSysIdx
                        + " obj=" + row.objectInternalId
                        + " msg=" + ex.getMessage());
                ex.printStackTrace(System.out);
            }
        });

        batchButton.setOnAction(e -> {
            int from;
            int to;
            try {
                from = Integer.parseInt(fromField.getText().trim());
                to = Integer.parseInt(toField.getText().trim());
            } catch (NumberFormatException ex) {
                saveStatus.setText("Batch range is invalid.");
                return;
            }
            if (from < 1 || to < from) {
                saveStatus.setText("Batch range is invalid.");
                return;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Batch Save");
            confirm.setHeaderText("Generate and save all planets/moons?");
            confirm.setContentText("Range: StarSystem_" + from + " .. StarSystem_" + to);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }

            saveButton.setDisable(true);
            batchButton.setDisable(true);
            saveStatus.setText("Batch running...");

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() {
                    runBatch(repo, surfaceRepo, from, to);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                saveStatus.setText("Batch done.");
                saveButton.setDisable(false);
                batchButton.setDisable(false);
            });
            task.setOnFailed(ev -> {
                Throwable ex2 = task.getException();
                saveStatus.setText("Batch failed: " + (ex2 != null ? ex2.getMessage() : "unknown"));
                saveButton.setDisable(false);
                batchButton.setDisable(false);
            });

            Thread th = new Thread(task, "batch-save");
            th.setDaemon(true);
            th.start();
        });

        dumpBatchButton.setOnAction(e -> {
            Path reqPath = DUMP_REQUEST_FILE;
            if (!Files.exists(reqPath)) {
                saveStatus.setText("Request file not found: " + reqPath);
                return;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Batch Dump");
            confirm.setHeaderText("Generate dumps by request file?");
            confirm.setContentText("File: " + reqPath);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }

            saveButton.setDisable(true);
            batchButton.setDisable(true);
            dumpBatchButton.setDisable(true);
            saveStatus.setText("Batch dump running...");

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    runDumpBatch(repo, reqPath);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                saveStatus.setText("Batch dump done.");
                saveButton.setDisable(false);
                batchButton.setDisable(false);
                dumpBatchButton.setDisable(false);
            });
            task.setOnFailed(ev -> {
                Throwable ex2 = task.getException();
                saveStatus.setText("Batch dump failed: " + (ex2 != null ? ex2.getMessage() : "unknown"));
                saveButton.setDisable(false);
                batchButton.setDisable(false);
                dumpBatchButton.setDisable(false);
            });

            Thread th = new Thread(task, "batch-dump");
            th.setDaemon(true);
            th.start();
        });

        HBox bar = new HBox(
                10,
                saveButton,
                new Label("Batch range:"),
                fromField,
                new Label(".."),
                toField,
                batchButton,
                new Label("Dump request:"),
                new Label(DUMP_REQUEST_FILE.toString()),
                dumpBatchButton,
                saveStatus
        );
        bar.setPadding(new Insets(8));
        return bar;
    }

    private void runBatch(StarSystemRepository repo,
                          PlanetSurfaceRepository surfaceRepo,
                          int from,
                          int to) {
        long batchStartMs = System.currentTimeMillis();
        int systemsOk = 0;
        int systemsFail = 0;
        appendBatchLog(BATCH_LOG_FILE, "[BATCH_START] from=" + from + " to=" + to
                + " parallel=" + System.getProperty("planet.climate.parallel", "true"));
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
                        int plateCount = PlanetTuning.plateCount(planet, worldType);

                        if (planet.lavaWorld) {
                            for (Tile t : tiles) {
                                t.surfaceType = SurfaceType.LAVA_OCEAN;
                                t.volcanism = 100;
                                t.temperature = 800;
                            }
                        } else {
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
                            pipeline.run(tiles, planet, settings, plateCount);
                        }

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

    private void runDumpBatch(StarSystemRepository repo, Path requestFile) throws Exception {
        List<String> lines = Files.readAllLines(requestFile, StandardCharsets.UTF_8);
        int ok = 0;
        int fail = 0;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            try {
                String[] parts = line.split("[,;\\t ]+");
                if (parts.length < 2) {
                    throw new IllegalArgumentException("Line " + (i + 1) + ": expected '<starSysIdx> <objectInternalId>'");
                }

                int sys = Integer.parseInt(parts[0]);
                int obj = Integer.parseInt(parts[1]);
                StarSystemObjectRow req = repo.loadObjectRow(sys, obj);
                if (req == null) {
                    throw new IllegalStateException("Object not found");
                }

                PlanetConfig planet = PlanetConfigMapper.fromDescription(req);
                MoonTideResolver.populateMoonTideSources(planet, req, repo);
                String tilesPath = TileSetSelector.pickTilesPath(planet.radiusKm);
                List<Tile> batchTiles = CsvTileLoader.load(tilesPath);
                WorldType worldType = WorldClassifier.classify(planet);
                int plateCount = PlanetTuning.plateCount(planet, worldType);

                if (planet.lavaWorld) {
                    for (Tile t : batchTiles) {
                        t.surfaceType = SurfaceType.LAVA_OCEAN;
                        t.volcanism = 100;
                        t.temperature = 800;
                    }
                } else {
                    long seed = computeSeed(req);
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
                    pipeline.run(batchTiles, planet, settings, plateCount);
                }

                Path dumpPath = dumpTilesForDebug(req, planet, batchTiles);
                System.out.println("[DUMP-BATCH] OK sys=" + sys + " obj=" + obj + " -> " + dumpPath);
                ok++;
            } catch (Exception ex) {
                System.err.println("[DUMP-BATCH] FAIL line=" + (i + 1) + " : " + ex.getMessage());
                fail++;
            }
        }

        System.out.println("[DUMP-BATCH] Done. ok=" + ok + " fail=" + fail + " file=" + requestFile);
    }
}
