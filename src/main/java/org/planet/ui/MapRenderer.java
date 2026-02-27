package org.planet.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import org.planet.core.generation.ResourcePresence;
import org.planet.core.generation.ResourceType;
import org.planet.core.model.RiverBaseType;
import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapRenderer {

    private final Canvas canvas;
    private final GraphicsContext g;

    public MapRenderer(Canvas canvas) {
        this.canvas = canvas;
        this.g = canvas.getGraphicsContext2D();
    }

    public enum DisplayMode {
        SURFACE,
        ELEVATION,
        TEMP,
        MOISTURE,
        WIND,
        WATER_RIVERS,
        RESOURCES,
        FERTILITY
    }

    public enum SeasonView {
        ANNUAL,
        SUMMER,
        WINTER
    }

    public enum ResourceLayerView {
        ALL,
        SURFACE,
        DEEP,
        VERY_DEEP
    }

    public void render(List<Tile> tiles,
                       DisplayMode mode,
                       SeasonView season,
                       ResourceType resourceType,
                       ResourceLayerView layerView,
                       boolean tidalLocked,
                       double subsolarLatDeg) {
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        Map<Integer, Tile> byId = new HashMap<>();
        for (Tile t : tiles) {
            byId.put(t.id, t);
        }

        double minElev = 0;
        double maxElev = 1;
        double minTemp = -30;
        double maxTemp = 40;
        double minMoist = 0;
        double maxMoist = 100;
        double minWind = 0;
        double maxWind = 20;
        if (mode == DisplayMode.ELEVATION) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (Tile t : tiles) {
                min = Math.min(min, t.elevation);
                max = Math.max(max, t.elevation);
            }
            minElev = min;
            maxElev = Math.max(min + 1, max);
        } else if (mode == DisplayMode.TEMP) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Tile t : tiles) {
                double tv = tempBySeason(t, season);
                min = Math.min(min, tv);
                max = Math.max(max, tv);
            }
            minTemp = min;
            maxTemp = Math.max(min + 1, max);
        } else if (mode == DisplayMode.MOISTURE) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Tile t : tiles) {
                double mv = moistureBySeason(t, season);
                min = Math.min(min, mv);
                max = Math.max(max, mv);
            }
            minMoist = min;
            maxMoist = Math.max(min + 1, max);
        } else if (mode == DisplayMode.WIND) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Tile t : tiles) {
                double wv = windAvgBySeason(t, season);
                min = Math.min(min, wv);
                max = Math.max(max, wv);
            }
            minWind = min;
            maxWind = Math.max(min + 1, max);
        }

        for (Tile tile : tiles) {
            double x = (tile.lon + 180) / 360 * canvas.getWidth();
            double y = (90 - tile.lat) / 180 * canvas.getHeight();

            Color fill = switch (mode) {
                case SURFACE -> color(tile.surfaceType);
                case ELEVATION -> elevationColor(tile.elevation, minElev, maxElev);
                case TEMP -> temperatureColor(tempBySeason(tile, season), minTemp, maxTemp);
                case MOISTURE -> moistureColor(moistureBySeason(tile, season), minMoist, maxMoist);
                case WIND -> windColor(windAvgBySeason(tile, season), minWind, maxWind);
                case WATER_RIVERS -> waterColor(tile);
                case RESOURCES -> resourceColor(tile, resourceType, layerView);
                case FERTILITY -> fertilityColor(tile);
            };

            boolean drawPointAfterRiver = (mode == DisplayMode.RESOURCES);
            if (!drawPointAfterRiver) {
                g.setFill(fill);
                g.fillOval(x - 2, y - 2, 4, 4);
            }

            if (mode == DisplayMode.WIND) {
                drawWindGlyph(g, tile, season, x, y);
            }

            if (tile.isRiver) {
                RiverBaseType base = tile.riverBaseType == null ? RiverBaseType.NONE : tile.riverBaseType;
                Color rc = (mode == DisplayMode.WATER_RIVERS)
                        ? Color.rgb(255, 70, 70)
                        : riverColor(base);
                g.setFill(rc);
                g.fillOval(x - 1, y - 1, 2, 2);

                if (tile.riverTo >= 0) {
                    Tile to = byId.get(tile.riverTo);
                    if (to != null) {
                        double tx = (to.lon + 180) / 360 * canvas.getWidth();
                        double ty = (90 - to.lat) / 180 * canvas.getHeight();
                        double w = riverWidth(tile.riverDischargeTps);
                        g.setStroke(Color.rgb(0, 0, 0, 0.35));
                        g.setLineWidth(w + 0.55);
                        g.setLineDashes(null);
                        g.strokeLine(x, y, tx, ty);
                        g.setStroke(rc);
                        g.setLineWidth(w);
                        g.setLineDashes(null);
                        g.strokeLine(x, y, tx, ty);
                        g.setLineDashes(null);
                    }
                }
            }

            if (drawPointAfterRiver) {
                // In resource mode keep deposits readable above river lines/markers.
                g.setFill(fill);
                g.fillOval(x - 2, y - 2, 4, 4);
            }
        }

        if (tidalLocked) {
            drawTidalTerminatorOverlay(subsolarLatDeg);
        }
    }

    private void drawTidalTerminatorOverlay(double subsolarLatDeg) {
        double subsolarLat = clamp(subsolarLatDeg, -89.9, 89.9);
        double lat0Rad = Math.toRadians(subsolarLat);
        double tanLat0 = Math.tan(lat0Rad);
        g.setStroke(Color.rgb(255, 70, 70, 0.92));
        g.setLineWidth(1.6);
        g.setLineDashes(8.0, 6.0);

        // Great-circle terminator: angular distance to subsolar point is 90 deg.
        // For each latitude phi: cos(lambda) = -tan(phi) * tan(phi0), lambda has two branches (+/-).
        double step = 1.0;
        double prevXL = Double.NaN;
        double prevYL = Double.NaN;
        double prevXR = Double.NaN;
        double prevYR = Double.NaN;
        for (double lat = -90.0; lat <= 90.0; lat += step) {
            double phi = Math.toRadians(lat);
            double arg = -Math.tan(phi) * tanLat0;
            if (arg < -1.0 || arg > 1.0) {
                prevXL = prevYL = prevXR = prevYR = Double.NaN;
                continue;
            }

            double lonAbs = Math.toDegrees(Math.acos(arg));
            double lonL = -lonAbs;
            double lonR = lonAbs;
            double xL = lonToX(lonL);
            double xR = lonToX(lonR);
            double y = latToY(lat);

            if (!Double.isNaN(prevXL)) {
                g.strokeLine(prevXL, prevYL, xL, y);
            }
            if (!Double.isNaN(prevXR)) {
                g.strokeLine(prevXR, prevYR, xR, y);
            }
            prevXL = xL;
            prevYL = y;
            prevXR = xR;
            prevYR = y;
        }
        g.setLineDashes(null);
    }

    private double lonToX(double lon) {
        return (lon + 180.0) / 360.0 * canvas.getWidth();
    }

    private double latToY(double lat) {
        return (90.0 - lat) / 180.0 * canvas.getHeight();
    }

    private void drawWindGlyph(GraphicsContext g, Tile tile, SeasonView season, double x, double y) {
        double wx = windXBySeason(tile, season);
        double wy = windYBySeason(tile, season);
        double mag = Math.sqrt(wx * wx + wy * wy);
        if (mag < 1e-6) return;

        double nx = wx / mag;
        double ny = wy / mag;

        // направление в экранных координатах (y вниз)
        double len = 5.0;
        double ex = x + nx * len;
        double ey = y - ny * len;

        // цвет направления
        Color dirColor = windDirColor(nx, ny);

        // точка направления
        g.setFill(dirColor);
        g.fillOval(x - 1.2, y - 1.2, 2.4, 2.4);

        // стрелка
        g.setStroke(dirColor);
        g.setLineWidth(0.6);
        g.strokeLine(x, y, ex, ey);

        // маленький наконечник
        double hx = -nx;
        double hy = -ny;
        double side = 1.6;
        g.strokeLine(ex, ey, ex + (hx - ny) * side, ey + (hy + nx) * side);
        g.strokeLine(ex, ey, ex + (hx + ny) * side, ey + (hy - nx) * side);
    }

    private Color windDirColor(double nx, double ny) {
        // nx, ny in world coords (y up). Determine dominant direction.
        double ax = Math.abs(nx);
        double ay = Math.abs(ny);
        if (ay >= ax) {
            return ny >= 0 ? Color.BLUE : Color.PURPLE; // north/south
        }
        return nx >= 0 ? Color.DEEPSKYBLUE : Color.RED; // east/west
    }

    public Tile pickNearestTile(List<Tile> tiles, double x, double y) {
        Tile best = null;
        double bestD = Double.POSITIVE_INFINITY;
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        for (Tile t : tiles) {
            double tx = (t.lon + 180) / 360 * w;
            double ty = (90 - t.lat) / 180 * h;
            double dx = tx - x;
            double dy = ty - y;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD) {
                bestD = d2;
                best = t;
            }
        }
        return best;
    }

    public String formatTooltip(Tile t, DisplayMode mode, SeasonView season, ResourceType resourceType, ResourceLayerView layerView) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("ID ").append(t.id)
                .append("  lat=").append(String.format("%.2f", t.lat))
                .append("  lon=").append(String.format("%.2f", t.lon))
                .append("\n");
        sb.append("Surface: ").append(t.surfaceType)
                .append("  Elev=").append(t.elevation)
                .append("  UnderwaterElev=").append(t.underwaterElevation)
                .append("  Volc=").append(t.volcanism)
                .append("\n");
        sb.append("Now Temp=").append(String.format("%.1f", tempBySeason(t, season)))
                .append("C  Precip=").append(String.format("%.1f", precipBySeason(t, season)))
                .append("  Evap=").append(String.format("%.1f", evapBySeason(t, season)))
                .append("\n");
        sb.append("Temp S/I/W=")
                .append(String.format("%.1f", tempBySeason(t, SeasonView.SUMMER))).append("/")
                .append(String.format("%.1f", tempBySeason(t, SeasonView.ANNUAL))).append("/")
                .append(String.format("%.1f", tempBySeason(t, SeasonView.WINTER))).append(" C")
                .append("\n");
        sb.append("Tmin S/I/W=")
                .append(String.format("%.1f", tempMinBySeason(t, SeasonView.SUMMER))).append("/")
                .append(String.format("%.1f", tempMinBySeason(t, SeasonView.ANNUAL))).append("/")
                .append(String.format("%.1f", tempMinBySeason(t, SeasonView.WINTER)))
                .append("  Tmax S/I/W=")
                .append(String.format("%.1f", tempMaxBySeason(t, SeasonView.SUMMER))).append("/")
                .append(String.format("%.1f", tempMaxBySeason(t, SeasonView.ANNUAL))).append("/")
                .append(String.format("%.1f", tempMaxBySeason(t, SeasonView.WINTER))).append(" C")
                .append("\n");
        sb.append("Precip S/I/W=")
                .append(String.format("%.2f", precipPhysBySeason(t, SeasonView.SUMMER))).append("/")
                .append(String.format("%.2f", precipPhysBySeason(t, SeasonView.ANNUAL))).append("/")
                .append(String.format("%.2f", precipPhysBySeason(t, SeasonView.WINTER)))
                .append(" kg/m2/day")
                .append("\n");
        sb.append("Soil S/I/W=")
                .append(String.format("%.1f", soilMoistBySeason(t, SeasonView.SUMMER))).append("/")
                .append(String.format("%.1f", soilMoistBySeason(t, SeasonView.ANNUAL))).append("/")
                .append(String.format("%.1f", soilMoistBySeason(t, SeasonView.WINTER))).append("%")
                .append("\n");
        sb.append("Solar S/I/W=")
                .append(formatEnergyPerDay(t.solarKwhDayWarm)).append("/")
                .append(formatEnergyPerDay(t.solarKwhDayInter)).append("/")
                .append(formatEnergyPerDay(t.solarKwhDayCold))
                .append(" (kWh/m2/day)")
                .append("\n");
        sb.append("Tide Range=")
                .append(String.format("%.2f", Double.isNaN(t.tidalRangeM) ? 0.0 : t.tidalRangeM))
                .append(" m  Period=")
                .append(String.format("%.2f", Double.isNaN(t.tidalPeriodHours) ? 0.0 : t.tidalPeriodHours))
                .append(" h  Cycles/day=")
                .append(String.format("%.2f", Double.isNaN(t.tidalCyclesPerDay) ? 0.0 : t.tidalCyclesPerDay))
                .append("\n");
        sb.append("Tide CoastAmpl=")
                .append(String.format("%.2f", Double.isNaN(t.tidalCoastAmplification) ? 0.0 : t.tidalCoastAmplification))
                .append("  WaterBody~")
                .append(String.format("%.0f", Double.isNaN(t.tidalWaterBodyScaleKm) ? 0.0 : t.tidalWaterBodyScaleKm))
                .append(" km")
                .append("\n");
        sb.append("FavoredSeason=").append(biomePreferredSeasonName(t.biomePreferredSeason))
                .append("  FavTemp=").append(String.format("%.1f", favoredTemp(t)))
                .append("C  FavAI=").append(String.format("%.2f", favoredAi(t)))
                .append("  FavSoil=").append(String.format("%.1f", favoredSoil(t))).append("%")
                .append("\n");
        sb.append("PrecipPhys=").append(String.format("%.2f", precipPhysBySeason(t, season)))
                .append(" kg/m2/day  EvapPhys=").append(String.format("%.2f", evapPhysBySeason(t, season)))
                .append(" kg/m2/day")
                .append("\n");
        sb.append("RunoffPhys=").append(String.format("%.2f", runoffPhysBySeason(t, season)))
                .append(" kg/m2/day")
                .append("\n");
        sb.append("AtmMoist=").append(String.format("%.1f", Double.isNaN(t.atmMoist) ? 0.0 : t.atmMoist))
                .append(" g/kg  Cap=").append(String.format("%.1f", saturationSpecificHumidity(t)))
                .append(" g/kg")
                .append("  Rel=").append(String.format("%.2f", relativeHumidity(t)))
                .append("  Wind=").append(String.format("%.1f", windAvgBySeason(t, season))).append("m/s")
                .append("  WindMax=").append(String.format("%.1f", windMaxBySeason(t, season))).append("m/s")
                .append("\n");
        sb.append("PotentialFlow Q=").append(String.format("%.0f", t.riverPotentialKgS)).append(" kg/s")
                .append(" (").append(String.format("%.1f", t.riverPotentialKgS / 1000.0)).append(" t/s)")
                .append("\n");
        sb.append("WaterFlow Q=").append(String.format("%.0f", t.riverDischargeKgS)).append(" kg/s")
                .append(" (").append(String.format("%.1f", t.riverDischargeTps)).append(" t/s)")
                .append("  flow=").append(String.format("%.2f", t.riverFlow))
                .append("\n");
        if (t.isRiver) {
            sb.append("River base=").append(t.riverBaseType)
                    .append("  Tag=").append(t.riverTag == null ? "" : t.riverTag)
                    .append("\n");
        }
        if (mode == DisplayMode.RESOURCES && resourceType != null) {
            if (layerView == ResourceLayerView.ALL) {
                boolean any = false;
                for (ResourcePresence r : t.resources) {
                    if (r.type != resourceType) continue;
                    any = true;
                    sb.append("Resource ").append(resourceType)
                            .append(" L=").append(r.layer)
                            .append(" Q=").append(r.quality)
                            .append(" S=").append(r.saturation)
                            .append(" A=").append(r.amount);
                    if (r.type == ResourceType.TIDAL_PWR) {
                        sb.append("  RangeM=").append(String.format("%.2f", r.logTonnes))
                                .append("  CyclesDay=").append(String.format("%.2f", r.tonnes))
                                .append("  PeriodH=").append(String.format("%.2f", Double.isNaN(t.tidalPeriodHours) ? 0.0 : t.tidalPeriodHours));
                    }
                    sb.append("\n");
                }
                if (!any) {
                    sb.append("Resource ").append(resourceType).append(": none\n");
                }
            } else {
                ResourcePresence rp = pickResource(t, resourceType, layerView);
                if (rp != null) {
                    sb.append("Resource ").append(resourceType)
                            .append(" L=").append(rp.layer)
                            .append(" Q=").append(rp.quality)
                            .append(" S=").append(rp.saturation)
                            .append(" A=").append(rp.amount);
                    if (rp.type == ResourceType.TIDAL_PWR) {
                        sb.append("  RangeM=").append(String.format("%.2f", rp.logTonnes))
                                .append("  CyclesDay=").append(String.format("%.2f", rp.tonnes))
                                .append("  PeriodH=").append(String.format("%.2f", Double.isNaN(t.tidalPeriodHours) ? 0.0 : t.tidalPeriodHours));
                    }
                    sb.append("\n");
                } else {
                    sb.append("Resource ").append(resourceType).append(": none\n");
                }
            }
        }
        if (mode == DisplayMode.FERTILITY) {
            ResourcePresence rp = null;
            for (ResourcePresence r : t.resources) {
                if (r.type == ResourceType.FERTILITY) {
                    rp = r;
                    break;
                }
            }
            if (rp != null) {
                sb.append("Fertility S=").append(rp.saturation)
                        .append(" Q=").append(rp.quality)
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private Color color(SurfaceType t) {
        return switch (t) {
            case OCEAN, SHALLOW_SEA, OPEN_WATER_DEEP -> Color.DARKBLUE;
            case OPEN_WATER_SHALLOW -> Color.DEEPSKYBLUE;
            case STEAM_SEA -> Color.LIGHTSTEELBLUE;
            case COAST_SANDY -> Color.BISQUE;
            case COAST_ROCKY -> Color.SIENNA;
            case ICE_OCEAN, SEA_ICE_SHALLOW, SEA_ICE_DEEP -> Color.LIGHTBLUE;
            case LAKE_FRESH -> Color.DODGERBLUE;
            case LAKE_SALT -> Color.STEELBLUE;
            case LAKE_BRINE -> Color.DARKSLATEBLUE;
            case LAKE_ACID -> Color.YELLOWGREEN;
            case LAVA_OCEAN -> Color.DARKRED;

            case PLAINS, PLAINS_GRASS, GRASSLAND -> Color.FORESTGREEN;
            case SAVANNA -> Color.YELLOWGREEN;
            case DRY_SAVANNA -> Color.KHAKI;
            case PLAINS_FOREST, FOREST -> Color.DARKGREEN;
            case RAINFOREST -> Color.SEAGREEN;

            case HILLS, HILLS_GRASS, HIGHLANDS -> Color.OLIVEDRAB;
            case HILLS_DRY_SAVANNA -> Color.SANDYBROWN;
            case HILLS_SAVANNA -> Color.DARKKHAKI;
            case HILLS_DESERT -> Color.TAN;
            case HILLS_TUNDRA -> Color.LIGHTSLATEGRAY;
            case HILLS_FOREST, HILLS_RAINFOREST, PLATEAU -> Color.DARKOLIVEGREEN;

            case MOUNTAINS, HIGH_MOUNTAINS -> Color.DIMGRAY;
            case MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST -> Color.DARKGREEN;
            case MOUNTAINS_TUNDRA -> Color.DARKKHAKI;
            case MOUNTAINS_DESERT -> Color.TAN;
            case MOUNTAINS_ALPINE -> Color.LIGHTGRAY;
            case ALPINE_MEADOW -> Color.LIGHTGREEN;
            case MOUNTAINS_SNOW -> Color.WHITESMOKE;

            case DESERT_SAND, SAND_DESERT -> Color.SANDYBROWN;
            case DESERT_ROCKY, ROCKY_DESERT, ROCK_DESERT -> Color.TAN;
            case DESERT, COLD_DESERT -> Color.KHAKI;

            case TUNDRA -> Color.DARKKHAKI;
            case PERMAFROST -> Color.LIGHTSLATEGRAY;

            case ICE, ICE_SHEET, GLACIER -> Color.ALICEBLUE;

            case SWAMP -> Color.DARKGREEN;
            case MUD_SWAMP -> Color.SADDLEBROWN;

            case VOLCANIC_FIELD, VOLCANIC, VOLCANO, ACTIVE_VOLCANO, LAVA_PLAINS, LAVA_ISLANDS, LAVA -> Color.ORANGERED;

            case REGOLITH -> Color.DARKGRAY;
            case CRATERED_SURFACE -> Color.GRAY;

            case METHANE_ICE -> Color.DARKTURQUOISE;
            case AMMONIA_ICE -> Color.LIGHTSEAGREEN;
            case CO2_ICE -> Color.GAINSBORO;
            case RIDGE -> Color.SLATEGRAY;
            case CANYON -> Color.SADDLEBROWN;
            case BASIN_FLOOR -> Color.DARKOLIVEGREEN;
            case RIDGE_ROCK -> Color.SLATEGRAY;
            case RIDGE_SNOW -> Color.WHITESMOKE;
            case RIDGE_TUNDRA -> Color.LIGHTSLATEGRAY;
            case RIDGE_GRASS -> Color.OLIVEDRAB;
            case RIDGE_FOREST -> Color.DARKOLIVEGREEN;
            case RIDGE_DESERT -> Color.TAN;
            case CANYON_ROCK -> Color.SADDLEBROWN;
            case CANYON_TUNDRA -> Color.DARKKHAKI;
            case CANYON_GRASS -> Color.OLIVEDRAB;
            case CANYON_FOREST -> Color.DARKGREEN;
            case CANYON_DESERT -> Color.SANDYBROWN;
            case BASIN_GRASS -> Color.FORESTGREEN;
            case BASIN_FOREST -> Color.DARKGREEN;
            case BASIN_DRY -> Color.KHAKI;
            case BASIN_SWAMP -> Color.DARKGREEN;
            case BASIN_TUNDRA -> Color.DARKKHAKI;
            case UNKNOWN -> Color.DARKSLATEGRAY;
        };

    }

    private Color riverColor(RiverBaseType base) {
        return switch (base) {
            case DELTA -> Color.LIGHTSKYBLUE;
            case WATERFALL -> Color.AQUA;
            case CANYON -> Color.NAVY;
            case VALLEY -> Color.ROYALBLUE;
            case VERY_LARGE_RIVER, LARGE_RIVER -> Color.DODGERBLUE;
            case MEDIUM_RIVER -> Color.DEEPSKYBLUE;
            case SMALL_RIVER, SOURCE -> Color.DEEPSKYBLUE;
            default -> Color.DEEPSKYBLUE;
        };
    }

    private double riverWidth(double tps) {
        if (tps >= 2500.0) return 4.8;
        if (tps >= 900.0) return 3.8;
        if (tps >= 250.0) return 2.8;
        if (tps >= 80.0) return 1.8;
        return 0.8;
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

    private double windAvg(Tile t) {
        if (!Double.isNaN(t.windAvg)) return t.windAvg;
        return Math.sqrt(t.windX * t.windX + t.windY * t.windY);
    }

    private double tempBySeason(Tile t, SeasonView season) {
        return switch (season) {
            case SUMMER -> Double.isNaN(t.tempWarm) ? t.temperature : t.tempWarm;
            case WINTER -> Double.isNaN(t.tempCold) ? t.temperature : t.tempCold;
            case ANNUAL -> t.temperature;
        };
    }

    private double moistureBySeason(Tile t, SeasonView season) {
        double precip = precipBySeason(t, season);
        double evap = evapBySeason(t, season);
        return clamp(50.0 + (precip - evap), 0.0, 100.0);
    }

    private double precipBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.precipWarm;
            case WINTER -> t.precipCold;
            case ANNUAL -> t.precipAvg;
        };
        if (Double.isNaN(v)) {
            v = Double.isNaN(t.precipAvg) ? 0.0 : t.precipAvg;
        }
        return v;
    }

    private double evapBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.evapWarm;
            case WINTER -> t.evapCold;
            case ANNUAL -> t.evapAvg;
        };
        if (Double.isNaN(v)) {
            v = Double.isNaN(t.evapAvg) ? 0.0 : t.evapAvg;
        }
        return v;
    }

    private double windAvgBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.windWarm;
            case WINTER -> t.windCold;
            case ANNUAL -> t.windAvg;
        };
        if (Double.isNaN(v) || v <= 0) {
            return windAvg(t);
        }
        return v;
    }

    private double precipPhysBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.precipKgM2DayWarm;
            case WINTER -> t.precipKgM2DayCold;
            case ANNUAL -> t.precipKgM2Day;
        };
        if (Double.isNaN(v)) {
            v = switch (season) {
                case SUMMER, WINTER -> t.precipKgM2DayInterseason;
                case ANNUAL -> t.precipKgM2Day;
            };
        }
        return Double.isNaN(v) ? 0.0 : v;
    }

    private double evapPhysBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.evapKgM2DayWarm;
            case WINTER -> t.evapKgM2DayCold;
            case ANNUAL -> t.evapKgM2Day;
        };
        if (Double.isNaN(v)) {
            v = switch (season) {
                case SUMMER, WINTER -> t.evapKgM2DayInterseason;
                case ANNUAL -> t.evapKgM2Day;
            };
        }
        return Double.isNaN(v) ? 0.0 : v;
    }

    private double runoffPhysBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.surfaceRunoffKgM2DayWarm;
            case WINTER -> t.surfaceRunoffKgM2DayCold;
            case ANNUAL -> t.surfaceRunoffKgM2Day;
        };
        if (Double.isNaN(v)) {
            v = switch (season) {
                case SUMMER, WINTER -> t.surfaceRunoffKgM2DayInterseason;
                case ANNUAL -> t.surfaceRunoffKgM2Day;
            };
        }
        return Double.isNaN(v) ? 0.0 : v;
    }

    private double soilMoistBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.moistureWarm;
            case WINTER -> t.moistureCold;
            case ANNUAL -> t.moisture;
        };
        return Double.isNaN(v) ? (Double.isNaN(t.moisture) ? 0.0 : t.moisture) : v;
    }

    private String biomePreferredSeasonName(int id) {
        return switch (id) {
            case 0 -> "INTERSEASON";
            case 1 -> "SUMMER";
            case 2 -> "WINTER";
            default -> "UNKNOWN";
        };
    }

    private double favoredTemp(Tile t) {
        return switch (t.biomePreferredSeason) {
            case 1 -> Double.isNaN(t.biomeTempWarm) ? t.temperature : t.biomeTempWarm;
            case 2 -> Double.isNaN(t.biomeTempCold) ? t.temperature : t.biomeTempCold;
            default -> Double.isNaN(t.biomeTempInterseason) ? t.temperature : t.biomeTempInterseason;
        };
    }

    private double favoredAi(Tile t) {
        return switch (t.biomePreferredSeason) {
            case 1 -> Double.isNaN(t.biomeAiWarm) ? 0.0 : t.biomeAiWarm;
            case 2 -> Double.isNaN(t.biomeAiCold) ? 0.0 : t.biomeAiCold;
            default -> Double.isNaN(t.biomeAiAnn) ? 0.0 : t.biomeAiAnn;
        };
    }

    private double favoredSoil(Tile t) {
        return switch (t.biomePreferredSeason) {
            case 1 -> Double.isNaN(t.biomeMoistureWarm) ? (Double.isNaN(t.moisture) ? 0.0 : t.moisture) : t.biomeMoistureWarm;
            case 2 -> Double.isNaN(t.biomeMoistureCold) ? (Double.isNaN(t.moisture) ? 0.0 : t.moisture) : t.biomeMoistureCold;
            default -> Double.isNaN(t.biomeMoistureInterseason) ? (Double.isNaN(t.moisture) ? 0.0 : t.moisture) : t.biomeMoistureInterseason;
        };
    }

    private double tempMinBySeason(Tile t, SeasonView season) {
        return switch (season) {
            case SUMMER -> Double.isNaN(t.tempMinWarm)
                    ? (Double.isNaN(t.tempWarm) ? t.temperature : t.tempWarm)
                    : t.tempMinWarm;
            case WINTER -> Double.isNaN(t.tempMinCold)
                    ? (Double.isNaN(t.tempCold) ? t.temperature : t.tempCold)
                    : t.tempMinCold;
            case ANNUAL -> Double.isNaN(t.tempMinInterseason)
                    ? (Double.isNaN(t.tempMin) ? t.temperature : t.tempMin)
                    : t.tempMinInterseason;
        };
    }

    private double tempMaxBySeason(Tile t, SeasonView season) {
        return switch (season) {
            case SUMMER -> Double.isNaN(t.tempMaxWarm)
                    ? (Double.isNaN(t.tempWarm) ? t.temperature : t.tempWarm)
                    : t.tempMaxWarm;
            case WINTER -> Double.isNaN(t.tempMaxCold)
                    ? (Double.isNaN(t.tempCold) ? t.temperature : t.tempCold)
                    : t.tempMaxCold;
            case ANNUAL -> Double.isNaN(t.tempMaxInterseason)
                    ? (Double.isNaN(t.tempMax) ? t.temperature : t.tempMax)
                    : t.tempMaxInterseason;
        };
    }

    private double windXBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.windXWarm;
            case WINTER -> t.windXCold;
            case ANNUAL -> t.windX;
        };
        return Double.isNaN(v) ? t.windX : v;
    }

    private double windMaxBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.windMaxWarm;
            case WINTER -> t.windMaxCold;
            case ANNUAL -> t.windMax;
        };
        if (Double.isNaN(v) || v <= 0.0) {
            return windAvgBySeason(t, season);
        }
        return v;
    }

    private double windYBySeason(Tile t, SeasonView season) {
        double v = switch (season) {
            case SUMMER -> t.windYWarm;
            case WINTER -> t.windYCold;
            case ANNUAL -> t.windY;
        };
        return Double.isNaN(v) ? t.windY : v;
    }

    private String formatEnergyPerDay(double kwhDay) {
        double v = Double.isNaN(kwhDay) ? 0.0 : Math.max(0.0, kwhDay);
        if (v >= 1.0e9) return String.format("%.2fG", v / 1.0e9);
        if (v >= 1.0e6) return String.format("%.2fM", v / 1.0e6);
        if (v >= 1.0e3) return String.format("%.2fk", v / 1.0e3);
        return String.format("%.1f", v);
    }

    private Color elevationColor(double elev, double min, double max) {
        double n = norm(elev, min, max);
        if (n < 0.25) return lerp(Color.DARKBLUE, Color.TURQUOISE, n / 0.25);
        if (n < 0.5) return lerp(Color.TURQUOISE, Color.GREEN, (n - 0.25) / 0.25);
        if (n < 0.8) return lerp(Color.GREEN, Color.SADDLEBROWN, (n - 0.5) / 0.3);
        return lerp(Color.SADDLEBROWN, Color.WHITE, (n - 0.8) / 0.2);
    }

    private Color temperatureColor(double temp, double min, double max) {
        double n = norm(temp, min, max);
        if (n < 0.35) return lerp(Color.DARKBLUE, Color.LIGHTBLUE, n / 0.35);
        if (n < 0.55) return lerp(Color.LIGHTBLUE, Color.WHITE, (n - 0.35) / 0.2);
        if (n < 0.75) return lerp(Color.WHITE, Color.GOLD, (n - 0.55) / 0.2);
        return lerp(Color.GOLD, Color.RED, (n - 0.75) / 0.25);
    }

    private Color moistureColor(double moist, double min, double max) {
        double n = norm(moist, min, max);
        if (n < 0.25) return lerp(Color.SANDYBROWN, Color.YELLOWGREEN, n / 0.25);
        if (n < 0.6) return lerp(Color.YELLOWGREEN, Color.GREEN, (n - 0.25) / 0.35);
        return lerp(Color.GREEN, Color.DARKGREEN, (n - 0.6) / 0.4);
    }

    private Color windColor(double wind, double min, double max) {
        double n = norm(wind, min, max);
        return lerp(Color.DARKGRAY, Color.MEDIUMPURPLE, n);
    }

    private Color waterColor(Tile t) {
        return switch (t.surfaceType) {
            case OCEAN, OPEN_WATER_DEEP -> Color.DARKBLUE;
            case OPEN_WATER_SHALLOW -> Color.DEEPSKYBLUE;
            case LAKE_FRESH -> Color.DODGERBLUE;
            case LAKE_SALT -> Color.STEELBLUE;
            case ICE_OCEAN, SEA_ICE_SHALLOW, SEA_ICE_DEEP, ICE -> Color.LIGHTBLUE;
            case SWAMP -> Color.DARKGREEN;
            case MUD_SWAMP -> Color.SADDLEBROWN;
            default -> Color.DARKGRAY;
        };
    }

    private Color resourceColor(Tile t, ResourceType type, ResourceLayerView layerView) {
        if (type == null) return Color.DARKGRAY;
        ResourcePresence rp = pickResource(t, type, layerView);
        if (rp == null) return Color.DARKGRAY;
        double n = clamp01(rp.saturation / 100.0);
        Color base = resourceBaseColor(type);
        return lerp(Color.BLACK, base, n);
    }

    private Color fertilityColor(Tile t) {
        ResourcePresence rp = null;
        for (ResourcePresence r : t.resources) {
            if (r.type == ResourceType.FERTILITY) {
                rp = r;
                break;
            }
        }
        if (rp == null) return Color.DARKGRAY;
        double n = clamp01(rp.saturation / 100.0);
        return lerp(Color.DARKRED, Color.LIMEGREEN, n);
    }

    private ResourcePresence pickResource(Tile t, ResourceType type, ResourceLayerView layerView) {
        ResourcePresence best = null;
        for (ResourcePresence r : t.resources) {
            if (r.type != type) continue;
            if (layerView != ResourceLayerView.ALL) {
                if (layerView == ResourceLayerView.SURFACE && r.layer != org.planet.core.generation.ResourceLayer.SURFACE) continue;
                if (layerView == ResourceLayerView.DEEP && r.layer != org.planet.core.generation.ResourceLayer.DEEP) continue;
                if (layerView == ResourceLayerView.VERY_DEEP && r.layer != org.planet.core.generation.ResourceLayer.VERY_DEEP) continue;
            }
            if (best == null || r.saturation > best.saturation) best = r;
        }
        return best;
    }

    private Color resourceBaseColor(ResourceType type) {
        return switch (type) {
            case Fe_HEM, Fe_MAG, Fe_SID, Fe_LIM, Fe_GOE, Fe_OOL, Fe_TMAG, Fe_SAND, Fe_BIF, Fe_META -> Color.DARKRED;
            case Cu_CHAL, Cu_BORN, Cu_CHZ, Cu_MAL, Cu_PORP, Cu_SKAR, Cu_VMS -> Color.ORANGE;
            case ZnPb_SULF, ZnPb_CARB, ZnPb_OX, ZnPb_SEDEX, ZnPb_MVT -> Color.MEDIUMPURPLE;
            case Au_QUAR, Au_PLAC -> Color.GOLD;
            case Ag_POLY -> Color.LIGHTGRAY;
            case PGM_NI, PGM_PLAC -> Color.LIGHTGRAY;
            case C_COAL, C_LIGN, C_GRAPH -> Color.DIMGRAY;
            case H2O_FRESH, H2O_SALT -> Color.DEEPSKYBLUE;
            case Na_SALT, K_SALT, Na_BRINE, Mg_BRINE, Li_BRINE, Ca_BRINE, Br_BRINE, I_BRINE -> Color.KHAKI;
            case HC_OIL_L, HC_OIL_H, HC_BITUM, HC_GAS, HC_COND, HC_SHALE -> Color.SADDLEBROWN;
            case REE_MON, REE_BAST, REE_ION, REE_APAT -> Color.DARKVIOLET;
            case U_URAN, U_SAND, U_PHOS, Th_MON -> Color.LIMEGREEN;
            case Si_QUAR, Si_SAND -> Color.BEIGE;
            case Ti_ILM, Ti_RUT, Ti_SAND -> Color.SLATEBLUE;
            case W_SCHE, W_WOLF, Mo_PORP, Sn_CASS, Sn_PLAC -> Color.DARKSLATEBLUE;
            case F_FLUOR, B_BORAT, S_PYR, S_GAS, P_PHOS -> Color.DARKORANGE;
            case WIND_PWR, SOLAR_PWR, HYDRO_PWR, TIDAL_PWR, GEO_HEAT -> Color.CYAN;
            case BIO_MAT -> Color.DARKSEAGREEN;
            default -> Color.GRAY;
        };
    }

    private double norm(double v, double min, double max) {
        if (Double.isNaN(v)) return 0.0;
        return clamp01((v - min) / (max - min));
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double relativeHumidity(Tile t) {
        double atm = Double.isNaN(t.atmMoist) ? 0.0 : t.atmMoist;
        double cap = saturationSpecificHumidity(t);
        return cap > 0.0 ? (atm / cap) : 0.0;
    }

    private double saturationVaporPressure(double tempC) {
        return 0.6108 * Math.exp((17.27 * tempC) / (tempC + 237.3));
    }

    private double saturationSpecificHumidity(Tile t) {
        double tempC = t.temperature;
        double p = pressureKPa(t.pressure);
        double es = saturationVaporPressure(tempC);
        double denom = Math.max(1e-6, p - 0.378 * es);
        double q = 0.622 * es / denom;
        return q * 1000.0;
    }

    private double pressureKPa(int pressureUnits) {
        double atm = Math.max(0.05, pressureUnits / 1000.0);
        return atm * 101.325;
    }

    private Color lerp(Color a, Color b, double t) {
        double tt = clamp01(t);
        return new Color(
                a.getRed() + (b.getRed() - a.getRed()) * tt,
                a.getGreen() + (b.getGreen() - a.getGreen()) * tt,
                a.getBlue() + (b.getBlue() - a.getBlue()) * tt,
                1.0
        );
    }

    public VBox buildLegend(DisplayMode mode, SeasonView season, ResourceType resourceType, ResourceLayerView layerView) {
        VBox box = new VBox(4);
        box.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-padding: 6;");
        box.getChildren().add(new Label("Legend: " + mode.name() + (mode == DisplayMode.RESOURCES ? " (" + resourceType + ", " + layerView + ")" : "")));

        switch (mode) {
            case SURFACE -> {
                addLegendItem(box, "Open Water Deep", Color.DARKBLUE);
                addLegendItem(box, "Open Water Shallow", Color.DEEPSKYBLUE);
                addLegendItem(box, "Steam Sea", Color.LIGHTSTEELBLUE);
                addLegendItem(box, "Lakes (fresh)", Color.DODGERBLUE);
                addLegendItem(box, "Lakes (salt)", Color.STEELBLUE);
                addLegendItem(box, "Lakes (brine)", Color.DARKSLATEBLUE);
                addLegendItem(box, "Lakes (acid)", Color.YELLOWGREEN);
                addLegendItem(box, "Coast (sandy)", Color.BISQUE);
                addLegendItem(box, "Coast (rocky)", Color.SIENNA);
                addLegendItem(box, "Ice", Color.ALICEBLUE);
                addLegendItem(box, "Plains/Grass", Color.FORESTGREEN);
                addLegendItem(box, "Savanna", Color.YELLOWGREEN);
                addLegendItem(box, "Forest", Color.DARKGREEN);
                addLegendItem(box, "Hills", Color.OLIVEDRAB);
                addLegendItem(box, "Mountains", Color.DIMGRAY);
                addLegendItem(box, "Ridge", Color.SLATEGRAY);
                addLegendItem(box, "Canyon", Color.SADDLEBROWN);
                addLegendItem(box, "Basin Floor", Color.DARKOLIVEGREEN);
                addLegendItem(box, "Desert", Color.SANDYBROWN);
                addLegendItem(box, "Volcanic", Color.ORANGERED);
            }
            case ELEVATION -> {
                addLegendItem(box, "Low", Color.DARKBLUE);
                addLegendItem(box, "Mid", Color.GREEN);
                addLegendItem(box, "High", Color.SADDLEBROWN);
                addLegendItem(box, "Peaks", Color.WHITE);
            }
            case TEMP -> {
                addLegendItem(box, "Cold", Color.DARKBLUE);
                addLegendItem(box, "Cool", Color.LIGHTBLUE);
                addLegendItem(box, "Mild", Color.WHITE);
                addLegendItem(box, "Warm", Color.GOLD);
                addLegendItem(box, "Hot", Color.RED);
            }
            case MOISTURE -> {
                addLegendItem(box, "Dry", Color.SANDYBROWN);
                addLegendItem(box, "Moderate", Color.YELLOWGREEN);
                addLegendItem(box, "Wet", Color.GREEN);
                addLegendItem(box, "Very Wet", Color.DARKGREEN);
            }
            case WIND -> {
                addLegendItem(box, "Low", Color.DARKGRAY);
                addLegendItem(box, "High", Color.MEDIUMPURPLE);
            }
            case WATER_RIVERS -> {
                addLegendItem(box, "Open Water Deep", Color.DARKBLUE);
                addLegendItem(box, "Open Water Shallow", Color.DEEPSKYBLUE);
                addLegendItem(box, "Steam Sea", Color.LIGHTSTEELBLUE);
                addLegendItem(box, "Lakes (fresh)", Color.DODGERBLUE);
                addLegendItem(box, "Lakes (salt)", Color.STEELBLUE);
                addLegendItem(box, "Lakes (brine)", Color.DARKSLATEBLUE);
                addLegendItem(box, "Lakes (acid)", Color.YELLOWGREEN);
                addLegendItem(box, "Coast (sandy)", Color.BISQUE);
                addLegendItem(box, "Coast (rocky)", Color.SIENNA);
                addLegendItem(box, "Ice", Color.LIGHTBLUE);
                addLegendItem(box, "Swamp", Color.DARKGREEN);
                addLegendItem(box, "Canyon", Color.SADDLEBROWN);
                addLegendItem(box, "Land", Color.DARKGRAY);
            }
            case RESOURCES -> {
                addLegendItem(box, "None", Color.DARKGRAY);
                addLegendItem(box, "Low", lerp(Color.BLACK, resourceBaseColor(resourceType), 0.3));
                addLegendItem(box, "Medium", lerp(Color.BLACK, resourceBaseColor(resourceType), 0.6));
                addLegendItem(box, "High", lerp(Color.BLACK, resourceBaseColor(resourceType), 0.9));
            }
            case FERTILITY -> {
                addLegendItem(box, "Low", Color.DARKRED);
                addLegendItem(box, "High", Color.LIMEGREEN);
            }
        }
        return box;
    }

    private void addLegendItem(VBox box, String label, Paint color) {
        Rectangle swatch = new Rectangle(12, 12, color);
        HBox row = new HBox(6, swatch, new Label(label));
        box.getChildren().add(row);
    }
}
