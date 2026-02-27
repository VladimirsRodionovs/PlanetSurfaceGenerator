package org.planet.core.generation;

import org.planet.core.model.SurfaceType;
import org.planet.core.model.Tile;
import org.planet.core.model.RiverBaseType;
import org.planet.core.model.config.GeneratorSettings;
import org.planet.core.model.config.PlanetConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class ResourceGenerator {

    private static final double BIO_MATERIAL_BASE_TONNES = 2.0e8; // hex ~110 km, ~250 t/ha AGB -> ~2.0e8 t
    private static final double EARTH_SOLAR_CONSTANT_W_M2 = 1361.0;
    private static final double EARTH_EQ_TEMP_K = 255.0;
    private static final double FALLBACK_TILE_AREA_M2 = 9.35e9;
    private static final double EARTH_MOON_MASS_EARTH = 0.0123000371;
    private static final double EARTH_MOON_AXIS_AU = 0.00256955529;

    public void generate(List<Tile> tiles, PlanetConfig planet, GeneratorSettings settings) {
        WorldType worldType = WorldClassifier.classify(planet);
        double tileAreaM2 = estimateTileAreaM2(tiles, planet);
        computeTidalPotential(tiles, planet, tileAreaM2);
        for (Tile t : tiles) {
            t.resources.clear();
            Random rnd = new Random(settings.seed + t.id * 31L);

            boolean ocean = isOpenWater(t.surfaceType);
            boolean nearWater = hasNeighborLiquidWater(t);
            double boundaryScore = plateBoundaryScore(t);
            double temp = weightedTemp(t);
            double precipPhys = weightedPrecipPhysical(t);
            double evapPhys = weightedEvapPhysical(t);
            double soilMoist = weightedSoilMoisture(t);
            double wind = (t.windAvg > 0) ? t.windAvg : Math.sqrt(t.windX * t.windX + t.windY * t.windY);
            double windMax = (t.windMax > 0) ? t.windMax : wind;
            double windPowerMetric = 0.75 * wind + 0.25 * Math.min(windMax, wind * 2.2);
            computeSolarPotentialKwh(t, planet);
            double solarScore = solarResourceScore(t);

            // Энергетика
            addIfScore(t, rnd, ResourceType.WIND_PWR, ResourceLayer.SURFACE, clamp01(windPowerMetric / 12.0), planet, worldType, tiles);
            addSolarByPhysicalScore(t, rnd, solarScore, planet, worldType, tiles);
            double geoScore = clamp01((t.volcanism / 100.0) * 0.7 + (t.tectonicStress / 100.0) * 0.3);
            if (boundaryScore > 0) geoScore = clamp01(geoScore + 0.2 * boundaryScore);
            addIfScore(t, rnd, ResourceType.GEO_HEAT, ResourceLayer.DEEP, geoScore, planet, worldType, tiles);

            double hydroScore = hydroPotentialScore(t, tiles);
            if (hydroScore > 0.15) {
                addIfScore(t, rnd, ResourceType.HYDRO_PWR, ResourceLayer.SURFACE, hydroScore, planet, worldType, tiles);
            } else if (nearWater && !ocean && soilMoist > 40 && t.elevation > 5) {
                addIfScore(t, rnd, ResourceType.HYDRO_PWR, ResourceLayer.SURFACE, clamp01(soilMoist / 100.0), planet, worldType, tiles);
            }
            boolean tidalEligible = isLiquidWater(t.surfaceType) || (!isLiquidWater(t.surfaceType) && hasNeighborLiquidWater(t));
            if (tidalEligible && !Double.isNaN(t.tidalRangeM) && t.tidalRangeM > 0.02) {
                addTidalByPhysicalMetrics(t, planet);
            }

            if (ocean) {
                addFixed(t, ResourceType.H2O_SALT, ResourceLayer.SURFACE, 80, 80, 90);
            } else if (soilMoist > 45 && precipPhys > 0.8 && temp > -10 && temp < 40) {
                double freshScore = clamp01(0.55 * clamp01(soilMoist / 100.0) + 0.45 * clamp01(precipPhys / 12.0));
                addIfScore(t, rnd, ResourceType.H2O_FRESH, ResourceLayer.SURFACE, freshScore, planet, worldType, tiles);
            }

            // Устья рек: пресно-солёные зоны
            if (t.riverBaseType == RiverBaseType.DELTA && nearWater) {
                addFixed(t, ResourceType.H2O_FRESH, ResourceLayer.SURFACE, 60, 60, 40);
                addFixed(t, ResourceType.H2O_SALT, ResourceLayer.SURFACE, 50, 50, 30);
                addIfScore(t, rnd, ResourceType.Na_BRINE, ResourceLayer.SURFACE, 0.3, planet, worldType, tiles);
            }

            if (planet.hasAtmosphere) {
                addIfScore(t, rnd, ResourceType.ATM_AIR, ResourceLayer.SURFACE, clamp01(planet.atmosphereDensity / 2.0), planet, worldType, tiles);
            }
            if (planet.hasAtmosphere && t.volcanism > 35) {
                double degas = clamp01((t.volcanism - 35.0) / 65.0);
                addIfScore(t, rnd, ResourceType.ATM_H2S, ResourceLayer.SURFACE, degas, planet, worldType, tiles);
            }

            // Магматические/ультраосновные
            double mag = clamp01(t.volcanism / 100.0);
            double stress = clamp01(t.tectonicStress / 100.0);
            double magScore = clamp01(mag * 0.6 + stress * 0.4 + 0.2 * boundaryScore);
            if (magScore > 0.4) {
                addPick(t, rnd, ResourceLayer.VERY_DEEP, magScore, planet, worldType, tiles,
                        ResourceType.Cr_MASS, ResourceType.Ni_ULTRA, ResourceType.PGM_NI, ResourceType.Ti_ILM, ResourceType.Fe_TMAG, ResourceType.Fe_MAG);
            }
            if (magScore > 0.45) {
                addPick(t, rnd, ResourceLayer.DEEP, magScore, planet, worldType, tiles,
                        ResourceType.Ni_SULF, ResourceType.Co_SULF, ResourceType.Cu_CHAL, ResourceType.Cu_BORN, ResourceType.Al_NEPH);
            }

            // Гидротермальные
            double hydroThermalScore = magScore;
            if (nearWater) hydroThermalScore = clamp01(hydroThermalScore + 0.1);
            if (hydroThermalScore > 0.35) {
                addPick(t, rnd, ResourceLayer.DEEP, hydroThermalScore, planet, worldType, tiles,
                        ResourceType.Cu_PORP, ResourceType.Mo_PORP, ResourceType.Cu_VMS, ResourceType.Au_QUAR, ResourceType.Ag_POLY, ResourceType.ZnPb_SULF,
                        ResourceType.Cu_CHAL, ResourceType.Cu_BORN, ResourceType.F_FLUOR, ResourceType.Al_ALUN);
            }
            // Супергенное окисление и вторичное обогащение в верхней зоне выветривания.
            if (!ocean && hydroThermalScore > 0.3 && temp > 12) {
                double supergene = clamp01((hydroThermalScore - 0.25) * 1.2) * clamp01((temp - 8.0) / 28.0);
                addPick(t, rnd, ResourceLayer.SURFACE, supergene, planet, worldType, tiles,
                        ResourceType.Cu_CHZ, ResourceType.Cu_MAL, ResourceType.ZnPb_OX, ResourceType.Fe_GOE, ResourceType.Fe_LIM, ResourceType.Fe_HEM);
            }

            // Рудные пояса вдоль границ плит (скарны/порфиры/вольфрам/олово)
            if (boundaryScore > 0.3) {
                double beltScore = clamp01(0.4 + 0.6 * boundaryScore);
                addPick(t, rnd, ResourceLayer.DEEP, beltScore, planet, worldType, tiles,
                        ResourceType.Cu_SKAR, ResourceType.Mo_PORP, ResourceType.W_WOLF, ResourceType.W_SCHE, ResourceType.Sn_CASS, ResourceType.U_URAN);
            }

            // Осадочные бассейны
            double sedMoist = clamp01(0.65 * clamp01(soilMoist / 100.0) + 0.35 * clamp01(precipPhys / 40.0));
            double sed = clamp01(sedMoist * 0.6 + (1.0 - Math.abs(t.lat) / 90.0) * 0.4);
            if (sed > 0.35) {
                addPick(t, rnd, ResourceLayer.DEEP, sed, planet, worldType, tiles,
                        ResourceType.Fe_BIF, ResourceType.Mn_CARB, ResourceType.Mn_PYR, ResourceType.Mn_FERR,
                        ResourceType.ZnPb_SEDEX, ResourceType.ZnPb_MVT, ResourceType.ZnPb_CARB,
                        ResourceType.P_PHOS, ResourceType.S_PYR, ResourceType.U_SAND, ResourceType.U_PHOS, ResourceType.Si_SAND);
            }
            // Сидериты в более восстановительных низинных бассейнах.
            if (!ocean && t.elevation <= 3 && soilMoist > 62 && precipPhys > 1.0 && precipPhys < 22.0
                    && temp > 4 && temp < 30 && t.volcanism < 40) {
                double sid = clamp01((soilMoist - 60.0) / 28.0)
                        * clamp01((22.0 - precipPhys) / 20.0)
                        * clamp01((30.0 - temp) / 20.0);
                addPick(t, rnd, ResourceLayer.DEEP, sid, planet, worldType, tiles,
                        ResourceType.Fe_SID, ResourceType.Mn_CARB, ResourceType.ZnPb_CARB);
            }
            // Оолитовые железняки и карбонатно-терригенные осадки в тёплых мелководьях.
            if ((ocean || (nearWater && t.elevation <= 2)) && Math.abs(t.lat) < 28 && sed > 0.5) {
                double oolitic = clamp01((sed - 0.45) / 0.55)
                        * clamp01((28.0 - Math.abs(t.lat)) / 28.0)
                        * 0.45;
                addPick(t, rnd, ResourceLayer.DEEP, oolitic, planet, worldType, tiles,
                        ResourceType.Fe_OOL, ResourceType.Fe_SID, ResourceType.ZnPb_MVT);
            }

            // Россыпи (низины + близость воды)
            if (!ocean && (nearWater || t.isRiver) && t.elevation < 6) {
                double plac = clamp01(0.30 + clamp01(soilMoist / 100.0) * 0.35 + (t.riverFlow > 0.0 ? 0.30 : 0.0));
                addPick(t, rnd, ResourceLayer.SURFACE, plac, planet, worldType, tiles,
                        ResourceType.Au_PLAC, ResourceType.Sn_PLAC, ResourceType.Ti_SAND, ResourceType.PGM_PLAC, ResourceType.Zr_CIRC, ResourceType.Fe_SAND,
                        ResourceType.Cr_PLAC, ResourceType.Ti_RUT, ResourceType.Th_MON, ResourceType.REE_MON);
            }
            if (!ocean && nearWater && t.elevation < 8) {
                double quartzSand = clamp01(0.2 + (1.0 - soilMoist / 120.0) + clamp01(t.riverFlow));
                addIfScore(t, rnd, ResourceType.Si_SAND, ResourceLayer.SURFACE, quartzSand, planet, worldType, tiles);
            }

            // Латериты: сезонно-взвешенная температура/влага ((warm + 2*inter + cold)/4).
            if (temp > 22 && precipPhys > 1.8 && soilMoist > 40) {
                double lat = clamp01((temp - 22.0) / 14.0)
                        * clamp01((precipPhys - 1.8) / 10.0)
                        * clamp01((soilMoist - 38.0) / 42.0);
                addPick(t, rnd, ResourceLayer.SURFACE, lat, planet, worldType, tiles,
                        ResourceType.Ni_LAT_L, ResourceType.Ni_LAT_S, ResourceType.Co_LATER, ResourceType.Al_BOX_L, ResourceType.Al_BOX_K, ResourceType.REE_ION);
                if (lat > 0.55) {
                    addPick(t, rnd, ResourceLayer.SURFACE, lat * 0.7, planet, worldType, tiles,
                            ResourceType.Al_BOX_L, ResourceType.Al_BOX_K, ResourceType.Ni_LAT_L, ResourceType.Ni_LAT_S);
                }
            }

            // Карст/испарение (сухие тёплые зоны)
            if (!ocean && soilMoist < 40 && precipPhys < 1.2 && temp > 15) {
                double evap = clamp01((1.2 - precipPhys) / 1.2)
                        * clamp01((40.0 - soilMoist) / 40.0)
                        * clamp01((evapPhys + 0.1) / (precipPhys + 0.2))
                        * clamp01((temp - 15.0) / 20.0);
                addPick(t, rnd, ResourceLayer.SURFACE, evap, planet, worldType, tiles,
                        ResourceType.Na_SALT, ResourceType.K_SALT, ResourceType.Li_BRINE, ResourceType.Mg_BRINE,
                        ResourceType.Ca_BRINE, ResourceType.Br_BRINE, ResourceType.I_BRINE, ResourceType.B_BORAT);
            }
            if (!ocean && t.volcanism > 55) {
                double sulfurGas = clamp01((t.volcanism - 45.0) / 55.0);
                addIfScore(t, rnd, ResourceType.S_GAS, ResourceLayer.SURFACE, sulfurGas, planet, worldType, tiles);
            }

            // Болота/водно-болотные зоны
            if (t.surfaceType == SurfaceType.SWAMP || t.surfaceType == SurfaceType.MUD_SWAMP) {
                addPick(t, rnd, ResourceLayer.SURFACE, 0.5, planet, worldType, tiles,
                        ResourceType.C_LIGN, ResourceType.HC_GAS, ResourceType.Na_BRINE);
            }

            // Агро-зоны и плодородие
            if (!ocean && t.surfaceType != SurfaceType.MOUNTAINS && t.surfaceType != SurfaceType.VOLCANIC) {
                AgriProfile ap = agriProfile(t);
                if (ap.zone >= 0) {
                    // зона как ресурс с качеством=zone*20, насыщенность=100
                    addFixed(t, ResourceType.AGRO_ZONE, ResourceLayer.SURFACE, ap.zone * 20, 100, 100);
                }

                if (ap.agroScore > 0.2) {
                    addWithScore(t, rnd, ResourceType.FERTILITY, ResourceLayer.SURFACE, ap.agroScore, planet, worldType, tiles);
                }
                if (ap.naturalScore > 0.2) {
                    addWithScore(t, rnd, ResourceType.FERTILITY_NAT, ResourceLayer.SURFACE, ap.naturalScore, planet, worldType, tiles);
                }

                // Строительные биоматериалы (лесные тайлы)
                if (isForestSurface(t.surfaceType)) {
                    double availability = clamp01(ap.naturalScore);
                    int amount = clampInt((int) Math.round(availability * 100.0), 1, 100);
                    addBiomaterial(t, rnd, amount);
                }
            }

            // Углеводороды (маркеры из БД + жизнь/органика + палео-влажность/шельф)
            if (worldType != WorldType.AIRLESS) {
                double org = clamp01(planet.organicsFrac + clamp01(soilMoist / 100.0) * 0.2 + (planet.hasLife ? 0.15 : 0.0));
                boolean heavyEnabled = planet.heavyHydrocarbons;
                boolean wantLight = planet.lightHydrocarbons || org > 0.2;
                boolean wantHeavy = heavyEnabled && org > 0.2;

                // Суша: учитываем текущую и "наследованную" влажность осадочного бассейна.
                if (!ocean) {
                    double basinMoistNow = clamp01(1.0 - Math.abs(soilMoist - 50.0) / 50.0);
                    double paleoWet = clamp01(
                            0.45 * clamp01((12.0 - t.elevation) / 12.0)
                                    + 0.35 * clamp01((t.isRiver ? 0.6 : 0.0) + t.riverFlow + (hasNeighborLiquidWater(t) ? 0.35 : 0.0))
                                    + 0.20 * clamp01(1.0 - t.volcanism / 100.0));
                    double basinMoist = clamp01(0.6 * basinMoistNow + 0.4 * paleoWet);
                    double basin = clamp01((8.0 - t.elevation) / 8.0)
                            * clamp01(1.0 - maxSlope(t) / 8.0)
                            * basinMoist
                            * clamp01(1.0 - t.volcanism / 80.0);
                    if (basin < 0.23) {
                        wantLight = false;
                        wantHeavy = false;
                    }

                    // целевой охват (низкий), ещё ниже если нет жизни
                    double baseProb = (planet.lightHydrocarbons || heavyEnabled) ? 0.08 : 0.03;
                    if (planet.lightHydrocarbons) baseProb += 0.03;
                    if (planet.hasLife) baseProb *= 1.25;
                    if (org > 0.25) baseProb *= 1.15;
                    if (!planet.hasLife) baseProb *= 0.5;
                    if (org < 0.15) baseProb *= 0.7;
                    double chance = clamp01(baseProb * basin);

                    double lightChance = clamp01(chance * (planet.lightHydrocarbons ? 1.2 : 1.0));
                    double heavyChance = clamp01(chance * 0.7);
                    double coalChance = clamp01(chance * 0.9);

                    if (wantLight && rnd.nextDouble() < lightChance) {
                        double lightScore = Math.max(0.3, org);
                        if (planet.hasLife || planet.lightHydrocarbons) {
                            // Young biospheres and light-HC markers bias toward lighter fractions and gas.
                            addPick(t, rnd, ResourceLayer.DEEP, lightScore, planet, worldType, tiles,
                                    ResourceType.HC_OIL_L, ResourceType.HC_OIL_L, ResourceType.HC_OIL_L,
                                    ResourceType.HC_GAS, ResourceType.HC_GAS,
                                    ResourceType.HC_COND, ResourceType.HC_SHALE);
                        } else {
                            addPick(t, rnd, ResourceLayer.DEEP, lightScore, planet, worldType, tiles,
                                    ResourceType.HC_OIL_L, ResourceType.HC_GAS, ResourceType.HC_COND, ResourceType.HC_SHALE);
                        }
                    }
                    if (wantHeavy && rnd.nextDouble() < heavyChance) {
                        addPick(t, rnd, ResourceLayer.DEEP, Math.max(0.25, org), planet, worldType, tiles,
                                ResourceType.HC_OIL_H, ResourceType.HC_BITUM);
                    }

                    // Уголь и тяжёлые фракции появляются только при heavy-маркере.
                    if (heavyEnabled) {
                        double paleoMoist = clamp01(0.6 * soilMoist + 0.4 * paleoWet * 100.0);
                        double coalMask = clamp01((10.0 - t.elevation) / 10.0)
                                * clamp01(1.0 - maxSlope(t) / 6.0)
                                * clamp01((paleoMoist - 30.0) / 50.0)
                                * clamp01(1.0 - t.volcanism / 70.0);
                        if (coalMask > 0.35 && rnd.nextDouble() < coalChance * coalMask) {
                            addPick(t, rnd, ResourceLayer.SURFACE, Math.max(0.25, org), planet, worldType, tiles,
                                    ResourceType.C_COAL, ResourceType.C_LIGN);
                        }
                    }
                } else {
                    // Океан: прибрежный и шельфовый канал для light HC (включая палео-побережья).
                    if (wantLight && hasNeighborLand(t)) {
                        int depth = Math.max(0, t.underwaterElevation);
                        double shelfMask = clamp01((10.0 - depth) / 10.0);       // <= 1 км
                        double upperSlopeMask = clamp01((30.0 - depth) / 20.0)   // до ~3 км
                                * clamp01((depth - 8.0) / 22.0);
                        double marineMask = Math.max(shelfMask, upperSlopeMask * 0.75);

                        if (marineMask > 0.0) {
                            double offshoreOrg = clamp01(org + 0.15 + (planet.hasLife ? 0.1 : 0.0));
                            double offshoreBase = 0.04 + (planet.lightHydrocarbons ? 0.03 : 0.0) + (planet.hasLife ? 0.02 : 0.0);
                            double offshoreChance = clamp01(offshoreBase * marineMask * clamp01(0.7 + offshoreOrg));
                            if (rnd.nextDouble() < offshoreChance) {
                                double score = Math.max(0.3, offshoreOrg);
                                if (depth <= 10) {
                                    addPick(t, rnd, ResourceLayer.DEEP, score, planet, worldType, tiles,
                                            ResourceType.HC_OIL_L, ResourceType.HC_OIL_L,
                                            ResourceType.HC_GAS, ResourceType.HC_GAS, ResourceType.HC_COND);
                                } else {
                                    addPick(t, rnd, ResourceLayer.DEEP, score, planet, worldType, tiles,
                                            ResourceType.HC_GAS, ResourceType.HC_GAS, ResourceType.HC_COND, ResourceType.HC_OIL_L);
                                }
                            }
                        }
                    }
                }
            }

            // Метаморфические
            double meta = clamp01((t.tectonicStress / 100.0) * 0.6 + (t.elevation / 50.0) * 0.4);
            if (meta > 0.4) {
                addPick(t, rnd, ResourceLayer.VERY_DEEP, meta, planet, worldType, tiles,
                        ResourceType.Fe_META, ResourceType.Fe_HEM, ResourceType.W_WOLF, ResourceType.W_SCHE, ResourceType.Si_QUAR, ResourceType.Au_QUAR,
                        ResourceType.C_GRAPH, ResourceType.Ti_RUT, ResourceType.U_URAN);
            }
            if (!ocean && (magScore > 0.3 || boundaryScore > 0.2)) {
                double alkaline = clamp01(0.5 * magScore + 0.5 * boundaryScore);
                addPick(t, rnd, ResourceLayer.DEEP, alkaline, planet, worldType, tiles,
                        ResourceType.REE_MON, ResourceType.REE_BAST, ResourceType.REE_APAT, ResourceType.Th_MON, ResourceType.Al_NEPH);
            }

            // Атмосферные газы (для ледяных/летучих миров)
            if (planet.hasAtmosphere && (worldType == WorldType.ICE_VOLATILE || worldType == WorldType.ICE_ROCKY)) {
                addIfScore(t, rnd, ResourceType.ATM_CO2, ResourceLayer.SURFACE, clamp01(planet.atmosphereDensity / 3.0), planet, worldType, tiles);
                if (planet.ammoniaIceFrac > 0.05) {
                    addIfScore(t, rnd, ResourceType.ATM_NH3, ResourceLayer.SURFACE, clamp01(planet.ammoniaIceFrac), planet, worldType, tiles);
                }
            }

            // Импактное стекло на кратерных поверхностях
            if (t.surfaceType == SurfaceType.CRATERED_SURFACE || t.surfaceType == SurfaceType.REGOLITH) {
                addIfScore(t, rnd, ResourceType.IMPACT_GLASS, ResourceLayer.SURFACE, 0.4, planet, worldType, tiles);
            }

            // Летучие льды как ресурс (по типу поверхности)
            if (t.surfaceType == SurfaceType.METHANE_ICE) {
                addIfScore(t, rnd, ResourceType.CH4_ICE_RES, ResourceLayer.SURFACE, 0.8, planet, worldType, tiles);
            } else if (t.surfaceType == SurfaceType.AMMONIA_ICE) {
                addIfScore(t, rnd, ResourceType.NH3_ICE_RES, ResourceLayer.SURFACE, 0.8, planet, worldType, tiles);
            } else if (t.surfaceType == SurfaceType.CO2_ICE) {
                addIfScore(t, rnd, ResourceType.CO2_ICE_RES, ResourceLayer.SURFACE, 0.8, planet, worldType, tiles);
            }

            // Криогенные ресурсы в глубине льда
            if (planet.subsurfaceIceThicknessMeters > 0 && (worldType == WorldType.ICE_VOLATILE || worldType == WorldType.ICE_ROCKY)) {
                double iceKm = planet.subsurfaceIceThicknessMeters / 1000.0;
                double iceScore = clamp01(iceKm / 5.0);

                ResourceLayer iceLayer = (iceKm > 2.0) ? ResourceLayer.VERY_DEEP : ResourceLayer.DEEP;
                addIfScore(t, rnd, ResourceType.H2O_ICE_RES, iceLayer, iceScore, planet, worldType, tiles);

                double vol = clamp01(planet.methaneIceFrac + planet.ammoniaIceFrac);
                if (vol > 0.05) {
                    double cryoScore = clamp01(iceScore * (0.5 + vol));
                    addIfScore(t, rnd, ResourceType.CRYO_VOLATILES, ResourceLayer.VERY_DEEP, cryoScore, planet, worldType, tiles);
                }
            }

            // Крио-выбросы от ударов на ледяных мирах
            if ((worldType == WorldType.ICE_VOLATILE || worldType == WorldType.ICE_ROCKY)
                    && (t.surfaceType == SurfaceType.CRATERED_SURFACE || t.surfaceType == SurfaceType.REGOLITH)) {
                addIfScore(t, rnd, ResourceType.H2O_ICE_RES, ResourceLayer.SURFACE, 0.35, planet, worldType, tiles);
                if (planet.methaneIceFrac > 0.02) {
                    addIfScore(t, rnd, ResourceType.CH4_ICE_RES, ResourceLayer.SURFACE, 0.3, planet, worldType, tiles);
                }
                if (planet.ammoniaIceFrac > 0.02) {
                    addIfScore(t, rnd, ResourceType.NH3_ICE_RES, ResourceLayer.SURFACE, 0.3, planet, worldType, tiles);
                }
            }
        }
        ResourceStatsReport.printToStdout(tiles);
    }

    private void addPick(Tile t, Random rnd, ResourceLayer layer, double score, PlanetConfig planet, WorldType worldType, List<Tile> tiles, ResourceType... types) {
        if (types.length == 0) return;
        ResourceType type = types[rnd.nextInt(types.length)];
        double s = clamp01(score * rarityMultiplier(type) * planetMultiplier(type, layer, planet, worldType) * chanceMultiplier(type));
        if (rnd.nextDouble() > s) return;
        addWithScore(t, rnd, type, layer, s, planet, worldType, tiles);
    }

    private void addIfScore(Tile t, Random rnd, ResourceType type, ResourceLayer layer, double score, PlanetConfig planet, WorldType worldType, List<Tile> tiles) {
        double s = clamp01(score * rarityMultiplier(type) * planetMultiplier(type, layer, planet, worldType) * chanceMultiplier(type));
        if (rnd.nextDouble() > s) return;
        addWithScore(t, rnd, type, layer, s, planet, worldType, tiles);
    }

    // Solar is now deterministic from physical insolation (kWh/m2/day), without random dropouts.
    private void addSolarByPhysicalScore(Tile t, Random rnd, double score, PlanetConfig planet, WorldType worldType, List<Tile> tiles) {
        double s = clamp01(score * planetMultiplier(ResourceType.SOLAR_PWR, ResourceLayer.SURFACE, planet, worldType));
        if (s <= 0.0) return;
        addWithScore(t, rnd, ResourceType.SOLAR_PWR, ResourceLayer.SURFACE, s, planet, worldType, tiles);
    }

    private void addFixed(Tile t, ResourceType type, ResourceLayer layer, int quality, int saturation, int amount) {
        t.resources.add(new ResourcePresence(type, layer, quality, saturation, amount));
    }

    private void addBiomaterial(Tile t, Random rnd, int availability) {
        int quality = clampInt(55 + (int) Math.round(availability * 0.35), 1, 100);
        int saturation = clampInt(availability, 1, 100);
        int amount = clampInt(availability, 1, 100);
        ResourcePresence rp = new ResourcePresence(ResourceType.BIO_MAT, ResourceLayer.SURFACE, quality, saturation, amount);

        double tonnes = BIO_MATERIAL_BASE_TONNES * (availability / 100.0);
        rp.logTonnes = tonnes > 0 ? Math.log10(tonnes) : 0.0;
        rp.tonnes = tonnes;
        mergeOrAddResource(t, rp);
    }

    private boolean isForestSurface(SurfaceType st) {
        return switch (st) {
            case PLAINS_FOREST, FOREST, RAINFOREST,
                    HILLS_FOREST, HILLS_RAINFOREST, MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST,
                    RIDGE_FOREST, CANYON_FOREST, BASIN_FOREST -> true;
            default -> false;
        };
    }

    private void addWithScore(Tile t, Random rnd, ResourceType type, ResourceLayer layer, double score, PlanetConfig planet, WorldType worldType, List<Tile> tiles) {
        double[] weights = layerWeights(type);
        if (weights == null) {
            DepositProfile profile = distributionProfile(type);
            if (profile.kind == DistributionKind.COMPACT) {
                addPresenceToTile(t, rnd, type, layer, score, planet, worldType, 0.0);
                return;
            }
            addDeposit(t, rnd, type, layer, score, planet, worldType, tiles, profile);
            return;
        }

        double maxW = 0.0;
        for (double w : weights) maxW = Math.max(maxW, w);
        DepositProfile profile = distributionProfile(type);
        double[] adjusted = weights.clone();
        if (profile.kind == DistributionKind.BASIN) {
            // низкие области -> более поверхностные залежи
            double basinDepth = clamp01((10.0 - t.elevation) / 10.0);
            adjusted[0] *= (0.6 + 0.6 * basinDepth);
            adjusted[1] *= (0.6 + 0.4 * (1.0 - basinDepth));
            adjusted[2] *= (0.5 + 0.8 * (1.0 - basinDepth));
        }

        for (int i = 0; i < adjusted.length; i++) {
            if (adjusted[i] <= 0) continue;
            ResourceLayer targetLayer = switch (i) {
                case 0 -> ResourceLayer.SURFACE;
                case 1 -> ResourceLayer.DEEP;
                default -> ResourceLayer.VERY_DEEP;
            };
            double layerScore = score * (adjusted[i] / maxW);
            if (profile.kind == DistributionKind.COMPACT) {
                addPresenceToTile(t, rnd, type, targetLayer, layerScore, planet, worldType, 0.0);
            } else {
                addDeposit(t, rnd, type, targetLayer, layerScore, planet, worldType, tiles, profile);
            }
        }
    }

    private boolean hasNeighborWater(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (isWater(n.surfaceType)) return true;
        }
        return false;
    }

    private boolean hasNeighborLiquidWater(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (isLiquidWater(n.surfaceType)) return true;
        }
        return false;
    }

    private boolean hasNeighborLand(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (!isWater(n.surfaceType)) return true;
        }
        return false;
    }

    private double hydroPotentialScore(Tile t, List<Tile> tiles) {
        if (!t.isRiver) return 0.0;
        if (t.riverTo < 0 || t.riverTo >= tiles.size()) return 0.0;
        Tile to = tiles.get(t.riverTo);

        double drop = Math.max(0.0, t.elevation - to.elevation);
        double dropScore = clamp01(drop / 8.0);
        double flowScore = clamp01(t.riverFlow);
        double orderScore = clamp01(t.riverOrder / 5.0);

        return clamp01(0.4 * flowScore + 0.4 * dropScore + 0.2 * orderScore);
    }

    private boolean isWater(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP,
                    STEAM_SEA -> true;
            default -> false;
        };
    }

    private boolean isLiquidWater(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    LAKE_FRESH, LAKE_SALT, LAKE_BRINE, LAKE_ACID,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP -> true;
            default -> false;
        };
    }

    private boolean isOpenWater(SurfaceType st) {
        return switch (st) {
            case OCEAN, ICE_OCEAN, LAVA_OCEAN,
                    OPEN_WATER_SHALLOW, OPEN_WATER_DEEP,
                    SEA_ICE_SHALLOW, SEA_ICE_DEEP -> true;
            default -> false;
        };
    }

    private AgriProfile agriProfile(Tile t) {
        double moist = weightedSoilMoisture(t);
        double temp = weightedTemp(t);
        double tMin = Double.isNaN(t.tempMin) ? temp - 8.0 : t.tempMin;
        double tMax = Double.isNaN(t.tempMax) ? temp + 8.0 : t.tempMax;
        int sunny = t.sunnyDays;

        // базовая пригодность по температуре
        double tempScore = clamp01(1.0 - Math.abs(temp - 20.0) / 22.0);
        if (tMin < -5) tempScore *= 0.6;
        if (tMax > 40) tempScore *= 0.7;

        // влажность почвы (слишком сухо/мокро хуже)
        double moistScore = clamp01(1.0 - Math.abs(moist - 55.0) / 35.0);
        if (moist < 20) moistScore *= 0.6;
        if (moist > 85) moistScore *= 0.7;

        // инсоляция: слишком мало — плохо, слишком много — иссушение
        double sunScore = clamp01((sunny - 120.0) / 160.0);
        if (sunny > 310) sunScore *= 0.8;

        // высота и уклон
        double elevScore = clamp01(1.0 - (t.elevation / 70.0));
        double slopeScore = clamp01(1.0 - (maxSlope(t) / 10.0));

        // тип поверхности
        double surfaceMult = switch (t.surfaceType) {
            case PLAINS, PLAINS_GRASS, PLAINS_FOREST -> 1.1;
            case HILLS, HILLS_GRASS, HILLS_FOREST, HILLS_RAINFOREST, HILLS_SAVANNA -> 0.9;
            case MOUNTAINS, MOUNTAINS_SNOW, HIGH_MOUNTAINS, MOUNTAINS_FOREST, MOUNTAINS_RAINFOREST, MOUNTAINS_TUNDRA, MOUNTAINS_DESERT, MOUNTAINS_ALPINE -> 0.3;
            case RIDGE -> 0.4;
            case CANYON -> 0.7;
            case BASIN_FLOOR -> 1.1;
            case RIDGE_ROCK, RIDGE_SNOW, RIDGE_TUNDRA, RIDGE_GRASS, RIDGE_FOREST, RIDGE_DESERT -> 0.4;
            case CANYON_ROCK, CANYON_TUNDRA, CANYON_GRASS, CANYON_FOREST, CANYON_DESERT -> 0.7;
            case BASIN_GRASS, BASIN_FOREST, BASIN_DRY, BASIN_SWAMP, BASIN_TUNDRA -> 1.05;
            case SWAMP -> 0.7;
            case MUD_SWAMP -> 0.6;
            case DESERT_SAND, DESERT_ROCKY, DESERT, ROCKY_DESERT, SAND_DESERT, ROCK_DESERT, COLD_DESERT, HILLS_DESERT -> 0.4;
            case VOLCANIC, VOLCANIC_FIELD, VOLCANO, LAVA_PLAINS, LAVA_ISLANDS -> 0.2;
            default -> 1.0;
        };

        // вулканические почвы: умеренно — плюс, слишком сильно — минус
        double volc = t.volcanism / 100.0;
        double volcBonus = (volc >= 0.1 && volc <= 0.4) ? 1.1 : (volc > 0.7 ? 0.7 : 1.0);

        double base = 0.3 * tempScore + 0.3 * moistScore + 0.15 * sunScore + 0.15 * elevScore + 0.1 * slopeScore;
        base *= surfaceMult * volcBonus;

        // агро-потенциал усиливаем поймами рек
        double riverBonus = (t.isRiver || t.riverFlow > 0.2) ? 1.3 : 1.0;
        double agroScore = clamp01(base * riverBonus);

        // естественная биота любит больше влаги и лесистость
        double natMoist = clamp01(1.0 - Math.abs(moist - 70.0) / 40.0);
        double naturalScore = clamp01(0.6 * agroScore + 0.4 * natMoist);

        int zone = classifyAgroZone(temp, moist, tMin, tMax);
        return new AgriProfile(agroScore, naturalScore, zone);
    }

    private int classifyAgroZone(double temp, double moist, double tMin, double tMax) {
        // 0 холодная, 1 умеренная, 2 жаркая влажная, 3 жаркая сухая, 4 аридная/пустынная
        if (tMax < 10) return 0;
        if (temp < 12) return 0;
        if (temp >= 12 && temp <= 24 && moist >= 35) return 1;
        if (temp > 24 && moist >= 55) return 2;
        if (temp > 24 && moist >= 25) return 3;
        if (moist < 25) return 4;
        return 1;
    }

    private static class AgriProfile {
        final double agroScore;
        final double naturalScore;
        final int zone;

        AgriProfile(double agroScore, double naturalScore, int zone) {
            this.agroScore = agroScore;
            this.naturalScore = naturalScore;
            this.zone = zone;
        }
    }

    private double maxSlope(Tile t) {
        if (t.neighbors == null || t.neighbors.isEmpty()) return 0.0;
        int max = 0;
        for (Tile n : t.neighbors) {
            int d = Math.abs(t.elevation - n.elevation);
            if (d > max) max = d;
        }
        return max;
    }

    private double plateBoundaryScore(Tile t) {
        if (t.neighbors == null) return 0.0;
        int diff = 0;
        for (Tile n : t.neighbors) {
            if (n.plateId != t.plateId) diff++;
        }
        if (diff == 0) return 0.0;
        double score = diff / (double) t.neighbors.size();
        return clamp01(score);
    }

    private boolean isPlateBoundary(Tile t) {
        if (t.neighbors == null) return false;
        for (Tile n : t.neighbors) {
            if (n.plateId != t.plateId) return true;
        }
        return false;
    }

    private double layerMultiplier(ResourceLayer layer) {
        return switch (layer) {
            case SURFACE -> 1.0;
            case DEEP -> 1.2;
            case VERY_DEEP -> 1.5;
        };
    }

    private double estimateTileAreaM2(List<Tile> tiles, PlanetConfig planet) {
        if (planet != null && planet.radiusKm > 10.0 && tiles != null && !tiles.isEmpty()) {
            double r = planet.radiusKm * 1000.0;
            return (4.0 * Math.PI * r * r) / tiles.size();
        }
        return FALLBACK_TILE_AREA_M2;
    }

    private void computeTidalPotential(List<Tile> tiles, PlanetConfig planet, double tileAreaM2) {
        if (tiles == null || tiles.isEmpty()) return;
        for (Tile t : tiles) {
            t.tidalRangeM = 0.0;
            t.tidalPeriodHours = 0.0;
            t.tidalCyclesPerDay = 0.0;
            t.tidalCoastAmplification = 0.0;
            t.tidalWaterBodyScaleKm = 0.0;
        }
        if (planet == null || planet.moonTideSources == null || planet.moonTideSources.isEmpty()) {
            planet.tidalOpenOceanRangeM = 0.0;
            planet.tidalCyclesPerDay = 0.0;
            planet.tidalDominantPeriodHours = 0.0;
            return;
        }

        double planetMassEarth = Math.max(planet.massEarth, 0.05);
        double radiusRatio = (planet.radiusKm > 10.0) ? (planet.radiusKm / 6371.0) : 1.0;

        double eqAmpSumM = 0.0;
        double cyclesWeighted = 0.0;
        double wSum = 0.0;
        double inclWeighted = 0.0;
        double dominantWeight = -1.0;
        double dominantCycles = 0.0;

        double rotationDegPerDay = resolveRotationDegPerDay(planet);
        int prograde = (planet.rotationPrograde == 0) ? -1 : 1;

        for (PlanetConfig.MoonTideSource moon : planet.moonTideSources) {
            if (moon == null) continue;
            if (moon.massEarth <= 0.0 || moon.orbitSemimajorAxisAU <= 0.0) continue;

            double hRel = (moon.massEarth / planetMassEarth)
                    / (EARTH_MOON_MASS_EARTH / 1.0)
                    * Math.pow(radiusRatio, 4.0)
                    * Math.pow(EARTH_MOON_AXIS_AU / moon.orbitSemimajorAxisAU, 3.0);
            double moonAmpM = 0.54 * Math.max(0.0, hRel);
            eqAmpSumM += moonAmpM;

            double relSkyRate = planet.tidalLocked
                    ? Math.abs(moon.meanMotionPerDay)
                    : Math.abs(rotationDegPerDay - prograde * moon.meanMotionPerDay);
            double cyclesPerDayMoon = clamp(relSkyRate / 180.0, 0.02, 24.0);
            cyclesWeighted += cyclesPerDayMoon * moonAmpM;
            wSum += moonAmpM;
            inclWeighted += Math.abs(moon.orbitInclinationDeg) * moonAmpM;
            if (moonAmpM > dominantWeight) {
                dominantWeight = moonAmpM;
                dominantCycles = cyclesPerDayMoon;
            }
            moon.forcingRelativeEarthMoon = earthMoonRelativeForcing(moon.massEarth, moon.orbitSemimajorAxisAU);
        }

        if (eqAmpSumM <= 0.0) {
            planet.tidalOpenOceanRangeM = 0.0;
            planet.tidalCyclesPerDay = 0.0;
            planet.tidalDominantPeriodHours = 0.0;
            return;
        }

        // Open-ocean tide range (crest-to-trough): 2 * equilibrium amplitude sum.
        double openOceanRangeM = 2.0 * eqAmpSumM;
        double globalCyclesPerDay = (wSum > 0.0) ? (cyclesWeighted / wSum) : dominantCycles;
        double meanOrbitInclDeg = (wSum > 0.0) ? (inclWeighted / wSum) : 0.0;
        globalCyclesPerDay = clamp(globalCyclesPerDay, 0.02, 24.0);
        double dominantPeriodHours = (dominantCycles > 0.0) ? (24.0 / dominantCycles) : 0.0;

        planet.tidalOpenOceanRangeM = openOceanRangeM;
        planet.tidalCyclesPerDay = globalCyclesPerDay;
        planet.tidalDominantPeriodHours = dominantPeriodHours;

        double tileSpanKm = Math.max(20.0, Math.sqrt(Math.max(tileAreaM2, 1.0)) / 1000.0);
        double gravity = (planet == null) ? 1.0 : Math.max(0.05, planet.gravity);
        double gravityWaveFactor = clamp(Math.sqrt(1.0 / gravity), 0.55, 3.2);
        for (Tile t : tiles) {
            boolean waterTile = isLiquidWater(t.surfaceType);
            boolean coastalLand = !waterTile && hasNeighborLiquidWater(t);
            if (!waterTile && !coastalLand) continue;
            double latRad = Math.toRadians(clamp(t.lat, -90.0, 90.0));
            double latFactor = 0.35 + 0.65 * Math.cos(latRad) * Math.cos(latRad);
            TidalGeometry tg = tidalGeometry(t, tileSpanKm, waterTile, coastalLand, meanOrbitInclDeg);
            double localRangeM = openOceanRangeM * latFactor * tg.amplification * gravityWaveFactor;

            t.tidalRangeM = Math.max(0.0, localRangeM);
            t.tidalCyclesPerDay = globalCyclesPerDay;
            t.tidalPeriodHours = (globalCyclesPerDay > 0.0) ? (24.0 / globalCyclesPerDay) : 0.0;
            t.tidalCoastAmplification = tg.amplification;
            t.tidalWaterBodyScaleKm = tg.fetchKm;
        }
    }

    private double resolveRotationDegPerDay(PlanetConfig planet) {
        if (planet == null) return 360.0;
        if (planet.rotationPeriodHours > 0.0) {
            return 360.0 * 24.0 / planet.rotationPeriodHours;
        }
        if (planet.orbitalMeanMotionPerDay > 0.0 && planet.tidalLocked) {
            return planet.orbitalMeanMotionPerDay;
        }
        return 360.0;
    }

    private TidalGeometry tidalGeometry(Tile t, double tileSpanKm, boolean waterTile, boolean coastalLand, double meanOrbitInclDeg) {
        int nCount = (t.neighbors == null) ? 0 : t.neighbors.size();
        if (nCount == 0) {
            return new TidalGeometry(tileSpanKm, 0.6);
        }
        int waterNb = 0;
        for (Tile n : t.neighbors) {
            if (isLiquidWater(n.surfaceType)) waterNb++;
        }
        double openFrac = waterNb / (double) nCount;
        double landFrac = 1.0 - openFrac;

        // Long-wave tide aligns with moon ecliptic forcing; at tile level use east-west fetch proxy.
        double fetchKm = directionalFetchKm(t, tileSpanKm, 22, meanOrbitInclDeg);
        double fetchNorm = clamp01(Math.log1p(fetchKm) / Math.log1p(4500.0));

        // Basin amplification: enclosed coasts amplify; open ocean dampens.
        double basinAmp = coastalLand
                ? (1.0 + 0.95 * landFrac)
                : (0.85 + 0.45 * landFrac);
        double openWaterAmp = waterTile ? (0.75 + 0.60 * openFrac) : (0.65 + 0.40 * openFrac);
        double amp = clamp((0.35 + 1.45 * fetchNorm) * basinAmp * openWaterAmp, 0.18, 4.2);
        return new TidalGeometry(fetchKm, amp);
    }

    private double directionalFetchKm(Tile source, double tileSpanKm, int maxDepth, double meanOrbitInclDeg) {
        if (source == null || source.neighbors == null || source.neighbors.isEmpty()) return 0;
        Set<Integer> seen = new HashSet<>();
        List<Tile> frontier = new ArrayList<>();
        List<Integer> depthFront = new ArrayList<>();
        for (Tile n : source.neighbors) {
            if (isLiquidWater(n.surfaceType) && seen.add(n.id)) {
                frontier.add(n);
                depthFront.add(1);
            }
        }
        if (isLiquidWater(source.surfaceType) && seen.add(source.id)) {
            frontier.add(source);
            depthFront.add(0);
        }
        double effKm = 0.0;
        for (int i = 0; i < frontier.size(); i++) {
            Tile cur = frontier.get(i);
            int d = depthFront.get(i);
            effKm += tileSpanKm * orientationByInclination(source, cur, meanOrbitInclDeg) / (1.0 + d * 0.18);
        }

        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<Tile> next = new ArrayList<>();
            List<Integer> nextDepth = new ArrayList<>();
            for (int i = 0; i < frontier.size(); i++) {
                Tile cur = frontier.get(i);
                int curDepth = depthFront.get(i);
                if (cur.neighbors == null) continue;
                for (Tile nn : cur.neighbors) {
                    if (!isLiquidWater(nn.surfaceType)) continue;
                    if (!seen.add(nn.id)) continue;
                    next.add(nn);
                    nextDepth.add(curDepth + 1);
                    effKm += tileSpanKm * orientationByInclination(source, nn, meanOrbitInclDeg) / (1.0 + (curDepth + 1) * 0.18);
                }
            }
            frontier = next;
            depthFront = nextDepth;
            if (seen.size() > 2600) break;
        }
        return Math.max(tileSpanKm, effKm);
    }

    private double orientationByInclination(Tile from, Tile to, double orbitInclDeg) {
        if (from == null || to == null) return 0.5;
        double dLat = Math.abs(to.lat - from.lat);
        double dLon = Math.abs(deltaLonDeg(to.lon - from.lon)) * Math.cos(Math.toRadians(clamp(from.lat, -89.0, 89.0)));
        if (dLon < 1e-6 && dLat < 1e-6) return 0.5;
        double ew = dLon / (dLon + dLat + 1e-6);
        double ns = dLat / (dLon + dLat + 1e-6);
        double incl = clamp(Math.abs(orbitInclDeg) / 90.0, 0.0, 1.0);
        // low inclination -> mostly east-west tidal axis; high inclination adds meridional component.
        return clamp((1.0 - incl) * ew + incl * ns, 0.0, 1.0);
    }

    private double deltaLonDeg(double dLon) {
        double x = dLon;
        while (x > 180.0) x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }

    private void addTidalByPhysicalMetrics(Tile t, PlanetConfig planet) {
        if (t == null) return;
        t.resources.removeIf(r -> r != null && r.type == ResourceType.TIDAL_PWR);

        double rangeM = Math.max(0.0, Double.isNaN(t.tidalRangeM) ? 0.0 : t.tidalRangeM);
        double cycles = Math.max(0.0, Double.isNaN(t.tidalCyclesPerDay) ? 0.0 : t.tidalCyclesPerDay);
        double fetchKm = Math.max(0.0, Double.isNaN(t.tidalWaterBodyScaleKm) ? 0.0 : t.tidalWaterBodyScaleKm);
        if (rangeM <= 0.0 || cycles <= 0.0) return;

        // Store direct physical-derived values (no random index scoring).
        int quality = clampInt((int) Math.round(rangeM * 10.0), 1, 100);     // decimeters of range
        int saturation = clampInt((int) Math.round(cycles * 20.0), 1, 100);  // cycles/day scaled
        int amount = clampInt((int) Math.round(fetchKm / 20.0), 1, 100);     // directional fetch scale

        ResourcePresence rp = new ResourcePresence(ResourceType.TIDAL_PWR, ResourceLayer.SURFACE, quality, saturation, amount);
        // For tidal resource, persist physical metrics in floating fields:
        // logTonnes -> tidal range (m), tonnes -> tidal cycles/day.
        rp.logTonnes = rangeM;
        rp.tonnes = cycles;
        t.resources.add(rp);
    }

    private static final class TidalGeometry {
        final double fetchKm;
        final double amplification;

        TidalGeometry(double fetchKm, double amplification) {
            this.fetchKm = fetchKm;
            this.amplification = amplification;
        }
    }

    private double earthMoonRelativeForcing(double moonMassEarth, double axisAu) {
        if (moonMassEarth <= 0.0 || axisAu <= 0.0) return 0.0;
        double base = EARTH_MOON_MASS_EARTH / Math.pow(EARTH_MOON_AXIS_AU, 3.0);
        if (base <= 0.0) return 0.0;
        double f = moonMassEarth / Math.pow(axisAu, 3.0);
        return f / base;
    }

    private void computeSolarPotentialKwh(Tile t, PlanetConfig planet) {
        double fluxRel = resolveStellarFluxRelative(planet);
        double solarConstant = EARTH_SOLAR_CONSTANT_W_M2 * fluxRel;
        double latRad = Math.toRadians(clamp(t.lat, -90.0, 90.0));
        double tiltRad = Math.toRadians(clamp(Math.abs(planet == null ? 0.0 : planet.axialTilt), 0.0, 89.5));
        double latSign = (t.lat >= 0.0) ? 1.0 : -1.0;

        // local summer/winter respect hemisphere: seasons swap between hemispheres.
        double declInter = 0.0;
        double declWarm = latSign * tiltRad;
        double declCold = -latSign * tiltRad;

        double atmFactor = atmosphericTransmission(planet);
        double cloudInter = cloudinessIndex(weightedPrecipPhysical(t), weightedEvapPhysical(t));
        double cloudWarm = cloudinessIndex(seasonalPrecip(t, true), seasonalEvap(t, true));
        double cloudCold = cloudinessIndex(seasonalPrecip(t, false), seasonalEvap(t, false));

        double interKwhM2Day = surfaceSolarKwhM2Day(solarConstant, latRad, declInter, atmFactor, cloudInter);
        double warmKwhM2Day = surfaceSolarKwhM2Day(solarConstant, latRad, declWarm, atmFactor, cloudWarm);
        double coldKwhM2Day = surfaceSolarKwhM2Day(solarConstant, latRad, declCold, atmFactor, cloudCold);

        // Store solar potential per 1 m2.
        t.solarKwhDayInter = Math.max(0.0, interKwhM2Day);
        t.solarKwhDayWarm = Math.max(0.0, warmKwhM2Day);
        t.solarKwhDayCold = Math.max(0.0, coldKwhM2Day);
    }

    private double solarResourceScore(Tile t) {
        double inter = (Double.isNaN(t.solarKwhDayInter) ? 0.0 : t.solarKwhDayInter);
        double warm = (Double.isNaN(t.solarKwhDayWarm) ? inter : t.solarKwhDayWarm);
        double cold = (Double.isNaN(t.solarKwhDayCold) ? inter : t.solarKwhDayCold);
        double annualLike = (warm + 2.0 * inter + cold) / 4.0; // kWh/m2/day
        return clamp01(annualLike / 6.0);
    }

    private double resolveStellarFluxRelative(PlanetConfig planet) {
        if (planet == null) return 1.0;
        if (planet.stellarFlux > 0.0) return planet.stellarFlux;
        if (planet.equilibriumTemperatureK > 0.0) {
            double rel = Math.pow(planet.equilibriumTemperatureK / EARTH_EQ_TEMP_K, 4.0);
            return clamp(rel, 0.05, 5.0);
        }
        return 1.0;
    }

    private double atmosphericTransmission(PlanetConfig planet) {
        double d = (planet == null) ? 1.0 : Math.max(0.0, planet.atmosphereDensity);
        return clamp(0.82 - 0.12 * (d - 1.0), 0.45, 0.92);
    }

    private double seasonalPrecip(Tile t, boolean warm) {
        double v = warm ? t.precipKgM2DayWarm : t.precipKgM2DayCold;
        if (Double.isNaN(v)) v = t.precipKgM2DayInterseason;
        if (Double.isNaN(v)) v = t.precipKgM2Day;
        return Math.max(0.0, Double.isNaN(v) ? 0.0 : v);
    }

    private double seasonalEvap(Tile t, boolean warm) {
        double v = warm ? t.evapKgM2DayWarm : t.evapKgM2DayCold;
        if (Double.isNaN(v)) v = t.evapKgM2DayInterseason;
        if (Double.isNaN(v)) v = t.evapKgM2Day;
        return Math.max(0.0, Double.isNaN(v) ? 0.0 : v);
    }

    private double cloudinessIndex(double precip, double evap) {
        double p = Math.max(0.0, precip);
        double e = Math.max(0.0, evap);
        double wetShare = p / (p + e + 1.0);
        double precipIntensity = p / (p + 5.0);
        return clamp(0.10 + 0.55 * wetShare + 0.25 * precipIntensity, 0.05, 0.95);
    }

    private double surfaceSolarKwhM2Day(double solarConstantWm2,
                                        double latRad,
                                        double declRad,
                                        double atmFactor,
                                        double cloudiness) {
        double toaDailyMeanWm2 = dailyMeanToaWm2(solarConstantWm2, latRad, declRad);
        // Empirical cloud transmittance (clear-sky -> ~1, overcast -> lower).
        double cloudFactor = clamp(1.0 - 0.75 * Math.pow(cloudiness, 3.0), 0.15, 1.0);
        return toaDailyMeanWm2 * atmFactor * cloudFactor * 24.0 / 1000.0;
    }

    private double dailyMeanToaWm2(double solarConstantWm2, double latRad, double declRad) {
        double cosH0 = -Math.tan(latRad) * Math.tan(declRad);
        double h0;
        if (cosH0 >= 1.0) {
            h0 = 0.0; // polar night
        } else if (cosH0 <= -1.0) {
            h0 = Math.PI; // polar day
        } else {
            h0 = Math.acos(cosH0);
        }
        double q = (solarConstantWm2 / Math.PI)
                * (h0 * Math.sin(latRad) * Math.sin(declRad)
                + Math.cos(latRad) * Math.cos(declRad) * Math.sin(h0));
        return Math.max(0.0, q);
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private double rarityMultiplier(ResourceType type) {
        return switch (type) {
            // very common
            case Si_SAND, Si_QUAR, H2O_SALT, H2O_FRESH, ATM_AIR, WIND_PWR, SOLAR_PWR -> 1.0;

            // common
            case Fe_HEM, Fe_MAG, Fe_SID, Fe_LIM, Fe_GOE, Fe_OOL, Fe_TMAG, Fe_SAND, Fe_BIF, Fe_META,
                    Mn_PYR, Mn_CARB, Mn_FERR,
                    Al_BOX_L, Al_BOX_K, Al_NEPH, Al_ALUN,
                    Na_SALT, K_SALT, Na_BRINE, Mg_BRINE, Li_BRINE, Ca_BRINE, Br_BRINE, I_BRINE,
                    C_COAL, C_LIGN, C_GRAPH,
                    F_FLUOR, B_BORAT -> 0.6;

            // rare
            case Cu_CHAL, Cu_BORN, Cu_CHZ, Cu_MAL, Cu_PORP, Cu_SKAR, Cu_VMS,
                    ZnPb_SULF, ZnPb_CARB, ZnPb_OX, ZnPb_SEDEX, ZnPb_MVT,
                    Ni_SULF, Ni_LAT_L, Ni_LAT_S, Ni_ULTRA,
                    Co_LATER, Co_SULF,
                    Ti_ILM, Ti_RUT, Ti_SAND,
                    Zr_CIRC, Sn_CASS, Sn_PLAC,
                    W_SCHE, W_WOLF, Mo_PORP,
                    Ag_POLY, Au_QUAR, Au_PLAC,
                    U_URAN, U_SAND, U_PHOS,
                    S_PYR, S_GAS,
                    HC_OIL_L, HC_OIL_H, HC_BITUM, HC_GAS, HC_COND, HC_SHALE,
                    GEO_HEAT, HYDRO_PWR, TIDAL_PWR,
                    FERTILITY, FERTILITY_NAT, AGRO_ZONE,
                    ATM_CO2, ATM_H2S, ATM_NH3,
                    CH4_ICE_RES, NH3_ICE_RES, CO2_ICE_RES,
                    H2O_ICE_RES, CRYO_VOLATILES -> 0.25;

            // ultra-rare
            case PGM_NI, PGM_PLAC, REE_MON, REE_BAST, REE_ION, REE_APAT, Th_MON, IMPACT_GLASS -> 0.1;

            default -> 0.25;
        };
    }

    private double planetMultiplier(ResourceType type, ResourceLayer layer, PlanetConfig planet, WorldType worldType) {
        double mult = 1.0;

        // металлические руды зависят от железа и камня
        if (isMetalOre(type)) {
            double iron = clamp(planet.fracIron, 0.0, 1.0);
            double rock = clamp(planet.fracRock, 0.0, 1.0);
            double base = 0.4 + iron * 0.9 + rock * 0.3;
            mult *= clamp(base, 0.2, 1.6);

            // ледяные миры снижают доступность на поверхности
            if (worldType == WorldType.ICE_VOLATILE || worldType == WorldType.ICE_ROCKY) {
                if (layer == ResourceLayer.SURFACE) mult *= 0.2;
                else if (layer == ResourceLayer.DEEP) mult *= 0.6;
            }

            // очень толстый лед - почти нет поверхностных руд
            if (planet.subsurfaceIceThicknessMeters > 1000 && layer == ResourceLayer.SURFACE) {
                mult *= 0.1;
            }
        }

        // летучие/льды в холодных мирах
        if (type == ResourceType.ATM_CO2 || type == ResourceType.ATM_NH3) {
            if (worldType == WorldType.ICE_VOLATILE) mult *= 1.5;
        }

        if (type == ResourceType.CH4_ICE_RES || type == ResourceType.NH3_ICE_RES || type == ResourceType.CO2_ICE_RES) {
            if (worldType == WorldType.ICE_VOLATILE) mult *= 2.0;
            else if (worldType == WorldType.ICE_ROCKY) mult *= 1.2;
            else mult *= 0.2;
        }

        if (type == ResourceType.H2O_ICE_RES || type == ResourceType.CRYO_VOLATILES) {
            if (worldType == WorldType.ICE_VOLATILE) mult *= 2.0;
            else if (worldType == WorldType.ICE_ROCKY) mult *= 1.5;
            else mult *= 0.2;
        }

        // гидро-ресурсы при отсутствии воды
        if ((type == ResourceType.H2O_FRESH || type == ResourceType.H2O_SALT) && planet.waterCoverageOrdinal == 0) {
            mult *= 0.2;
        }

        // безатмосферные: меньше гидротермальных, больше импактных
        if (worldType == WorldType.AIRLESS) {
            if (type == ResourceType.IMPACT_GLASS) mult *= 2.5;
            if (type == ResourceType.PGM_NI || type == ResourceType.PGM_PLAC || type == ResourceType.REE_MON
                    || type == ResourceType.REE_BAST || type == ResourceType.REE_ION || type == ResourceType.REE_APAT) {
                mult *= 1.5;
            }
            if (type == ResourceType.Cu_PORP || type == ResourceType.Cu_VMS || type == ResourceType.Mo_PORP) {
                mult *= 0.6;
            }
        }

        // лавовые миры: больше магматических и геотермии, меньше воды/био
        if (worldType == WorldType.LAVA_WORLD) {
            if (type == ResourceType.GEO_HEAT || type == ResourceType.Ni_ULTRA || type == ResourceType.Cr_MASS
                    || type == ResourceType.Ti_ILM || type == ResourceType.Fe_TMAG
                    || type == ResourceType.Cu_PORP || type == ResourceType.Mo_PORP || type == ResourceType.Cu_VMS) {
                mult *= 1.6;
            }
            if (type == ResourceType.H2O_FRESH || type == ResourceType.H2O_SALT
                    || type == ResourceType.C_COAL || type == ResourceType.C_LIGN || type == ResourceType.HC_OIL_L
                    || type == ResourceType.HC_GAS || type == ResourceType.HC_SHALE
                    || type == ResourceType.FERTILITY || type == ResourceType.FERTILITY_NAT || type == ResourceType.AGRO_ZONE) {
                mult *= 0.2;
            }
        }

        return mult;
    }

    private boolean isMetalOre(ResourceType type) {
        return switch (type) {
            case Fe_HEM, Fe_MAG, Fe_SID, Fe_LIM, Fe_GOE, Fe_OOL, Fe_TMAG, Fe_SAND, Fe_BIF, Fe_META,
                    Mn_PYR, Mn_CARB, Mn_FERR,
                    Cr_MASS, Cr_PLAC,
                    Cu_CHAL, Cu_BORN, Cu_CHZ, Cu_MAL, Cu_PORP, Cu_SKAR, Cu_VMS,
                    ZnPb_SULF, ZnPb_CARB, ZnPb_OX, ZnPb_SEDEX, ZnPb_MVT,
                    Ni_SULF, Ni_LAT_L, Ni_LAT_S, Ni_ULTRA,
                    Co_LATER, Co_SULF,
                    Al_BOX_L, Al_BOX_K, Al_NEPH, Al_ALUN,
                    Ti_ILM, Ti_RUT, Ti_SAND,
                    Zr_CIRC,
                    Sn_CASS,
                    W_SCHE, W_WOLF,
                    Mo_PORP,
                    Au_QUAR, Au_PLAC,
                    Ag_POLY,
                    REE_MON, REE_BAST, REE_ION, REE_APAT,
                    U_URAN, U_SAND, U_PHOS,
                    Th_MON,
                    P_PHOS -> true;
            default -> false;
        };
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void addDeposit(Tile seed, Random rnd, ResourceType type, ResourceLayer layer, double score,
                            PlanetConfig planet, WorldType worldType, List<Tile> tiles, DepositProfile profile) {
        int min = profile.minHex;
        int max = profile.maxHex;
        int size = clampInt(min + rnd.nextInt(max - min + 1), 1, 300);
        double sizeScale = clamp01(0.6 + 0.8 * score);
        size = clampInt((int) Math.round(size * sizeScale), 1, max);

        List<DepositTile> deposit = expandDeposit(seed, size, profile, rnd, tiles);
        for (DepositTile dt : deposit) {
            double falloff = 1.0 - clamp01(dt.dist / (double) Math.max(1, profile.maxHex));
            double localScore = clamp01(score * (0.6 + 0.4 * falloff));
            addPresenceToTile(dt.tile, rnd, type, layer, localScore, planet, worldType, dt.dist * 0.2);
        }
    }

    private void addPresenceToTile(Tile t, Random rnd, ResourceType type, ResourceLayer layer, double score,
                                   PlanetConfig planet, WorldType worldType, double distancePenalty) {
        int[] qRange = qualityRange(type);
        int qMin = qRange[0];
        int qMax = qRange[1];
        int sMin = 5;
        int sMax = 100;

        if (type == ResourceType.WIND_PWR || type == ResourceType.SOLAR_PWR || type == ResourceType.HYDRO_PWR) {
            qMin = 20; sMin = 20;
        }
        if (type == ResourceType.H2O_FRESH) {
            qMin = 40; sMin = 30;
        }
        if (type == ResourceType.ATM_AIR || type == ResourceType.ATM_CO2 || type == ResourceType.ATM_NH3) {
            qMin = 30; sMin = 40;
        }

        double baseQ = qMin + (qMax - qMin) * (0.35 + 0.5 * score) + rnd.nextGaussian() * 4.0 - distancePenalty;
        double modQ = qualityModifiers(t, type, layer, score, planet, worldType);
        double layerAdj = layerQualityAdjust(type, layer);
        int quality = clampInt((int) Math.round(baseQ + modQ + layerAdj), 1, 100);

        double sat = sMin + (sMax - sMin) * score + rnd.nextGaussian() * 6;
        sat = sat * layerSaturationAdjust(type, layer);
        int saturation = clampInt((int) Math.round(sat), 1, 100);
        int amount = clampInt((int) Math.round(saturation * layerMultiplier(layer)), 1, 100);
        int adjustedAmount = applyAmountJitter(amount, rnd);
        ResourcePresence rp = new ResourcePresence(type, layer, quality, saturation, adjustedAmount);
        rp.logTonnes = computeLogTonnes(type, layer, adjustedAmount, quality, saturation, rnd);
        double tonnageMult = tonnageMultiplier(type);
        if (tonnageMult > 1.0 && rp.logTonnes > 0.0) {
            rp.logTonnes += Math.log10(tonnageMult);
        }
        rp.tonnes = computeTonnes(type, rp.logTonnes);
        mergeOrAddResource(t, rp);
    }

    private double chanceMultiplier(ResourceType type) {
        return switch (type) {
            // severe deficits: add frequency after amount boost.
            case HC_OIL_L, HC_OIL_H, HC_COND, HC_SHALE -> 2.6;
            case HC_GAS -> 10.4;
            case Cr_MASS, Cr_PLAC -> 4.2;
            case Au_QUAR, Au_PLAC -> 8.3;
            case Ag_POLY -> 13.9;
            case PGM_NI, PGM_PLAC -> 41.7;
            case Th_MON -> 277.8;
            default -> 1.0;
        };
    }

    private double tonnageMultiplier(ResourceType type) {
        return switch (type) {
            // near-threshold deficits: primarily fix by deposit size.
            case REE_MON, REE_BAST, REE_ION, REE_APAT -> 1.24;
            case P_PHOS -> 1.385;
            case Mg_BRINE -> 1.673;
            case U_URAN, U_SAND, U_PHOS -> 1.814;
            case Ti_ILM, Ti_RUT, Ti_SAND -> 1.832;
            case Cu_CHAL, Cu_BORN, Cu_CHZ, Cu_MAL, Cu_PORP, Cu_SKAR, Cu_VMS -> 3.5;
            case Ca_BRINE -> 5.341;

            // severe deficits: cap amount boost, rest via chance multipliers.
            case HC_OIL_L, HC_OIL_H, HC_COND, HC_SHALE, HC_GAS -> 6.0;
            case Cr_MASS, Cr_PLAC -> 6.0;
            case Au_QUAR, Au_PLAC -> 6.0;
            case Ag_POLY -> 6.0;
            case PGM_NI, PGM_PLAC -> 6.0;
            case Th_MON -> 6.0;
            default -> 1.0;
        };
    }

    private List<DepositTile> expandDeposit(Tile seed, int targetSize, DepositProfile profile, Random rnd, List<Tile> tiles) {
        List<DepositTile> result = new ArrayList<>();
        int n = tiles.size();
        boolean[] used = new boolean[n];

        result.add(new DepositTile(seed, 0));
        used[seed.id] = true;

        List<DepositTile> frontier = new ArrayList<>();
        frontier.add(new DepositTile(seed, 0));

        while (result.size() < targetSize && !frontier.isEmpty()) {
            DepositTile base = frontier.get(rnd.nextInt(frontier.size()));
            Tile current = base.tile;
            List<Tile> candidates = new ArrayList<>();
            if (profile.kind == DistributionKind.LINE) {
                // вероятность прекратить рост линии с дистанцией
                double continueProb = clamp01(0.95 - 0.02 * base.dist);
                if (rnd.nextDouble() > continueProb) {
                    frontier.remove(base);
                    continue;
                }
                candidates = lineCandidates(current, base.prev, used, seed, n, tiles);
            } else {
                for (Tile nb : current.neighbors) {
                    if (nb == null || nb.id < 0 || nb.id >= n) continue;
                    if (used[nb.id]) continue;
                    if (!depositSuitability(profile.kind, nb, seed)) continue;
                    candidates.add(nb);
                }
            }
            if (candidates.isEmpty()) {
                frontier.remove(base);
                continue;
            }
            Tile pick = pickWithDirectionBias(candidates, current, base.prev, rnd);
            int dist = base.dist + 1;
            result.add(new DepositTile(pick, dist, current));
            used[pick.id] = true;
            frontier.add(new DepositTile(pick, dist, current));

            // редкое ветвление для LINE-депозитов
            if (profile.kind == DistributionKind.LINE && result.size() < targetSize && candidates.size() > 1) {
                if (rnd.nextDouble() < 0.22) {
                    Tile alt = pickAlternate(candidates, pick, current, base.prev, rnd);
                    if (alt != null && !used[alt.id]) {
                        result.add(new DepositTile(alt, dist, current));
                        used[alt.id] = true;
                        frontier.add(new DepositTile(alt, dist, current));
                    }
                }
            }
        }
        return result;
    }

    private boolean depositSuitability(DistributionKind kind, Tile t, Tile seed) {
        return switch (kind) {
            case LINE -> t.isRiver || hasNeighborLiquidWater(t);
            case BELT -> isPlateBoundary(t) || t.tectonicStress > 40;
            case BASIN -> t.elevation <= seed.elevation + 4 && weightedSoilMoisture(t) > 20.0;
            case PATCHY -> true;
            case CLUSTER -> true;
            case COMPACT -> true;
        };
    }

    private DepositProfile distributionProfile(ResourceType type) {
        return switch (type) {
            // belts / basins
            case Fe_BIF -> new DepositProfile(DistributionKind.BELT, 20, 150);
            case Fe_OOL -> new DepositProfile(DistributionKind.BELT, 12, 80);
            case ZnPb_SEDEX -> new DepositProfile(DistributionKind.BELT, 15, 120);
            case U_SAND, U_PHOS, P_PHOS -> new DepositProfile(DistributionKind.BELT, 10, 80);

            case Ni_LAT_L, Ni_LAT_S, Co_LATER -> new DepositProfile(DistributionKind.BASIN, 30, 200);
            case Al_BOX_L, Al_BOX_K -> new DepositProfile(DistributionKind.BASIN, 30, 200);
            case Si_SAND -> new DepositProfile(DistributionKind.BASIN, 20, 120);
            case C_COAL -> new DepositProfile(DistributionKind.BASIN, 30, 200);
            case C_LIGN -> new DepositProfile(DistributionKind.BASIN, 40, 240);
            case B_BORAT -> new DepositProfile(DistributionKind.BASIN, 20, 120);
            case Na_SALT, K_SALT -> new DepositProfile(DistributionKind.BASIN, 20, 150);
            case Li_BRINE, Mg_BRINE, Br_BRINE, I_BRINE, Na_BRINE, Ca_BRINE -> new DepositProfile(DistributionKind.BASIN, 30, 200);
            case HC_OIL_L, HC_OIL_H, HC_COND, HC_GAS, HC_SHALE -> new DepositProfile(DistributionKind.BASIN, 20, 200);
            case HC_BITUM -> new DepositProfile(DistributionKind.PATCHY, 5, 40);
            case REE_ION -> new DepositProfile(DistributionKind.BASIN, 20, 150);

            // clusters
            case Fe_HEM, Fe_MAG, Fe_SID, Fe_LIM, Fe_GOE, Fe_META,
                    Mn_PYR, Mn_CARB, Mn_FERR,
                    ZnPb_CARB, ZnPb_OX, ZnPb_MVT,
                    Al_NEPH, Al_ALUN,
                    C_GRAPH -> new DepositProfile(DistributionKind.CLUSTER, 3, 20);
            case Cu_PORP, Mo_PORP -> new DepositProfile(DistributionKind.CLUSTER, 3, 15);

            // lines / placers
            case Fe_SAND, Ti_SAND, Zr_CIRC, Au_PLAC, Sn_PLAC, PGM_PLAC -> new DepositProfile(DistributionKind.LINE, 3, 30);

            // compact (default)
            case Cr_MASS, Cr_PLAC,
                    Cu_CHAL, Cu_BORN, Cu_CHZ, Cu_MAL, Cu_SKAR, Cu_VMS,
                    Ni_SULF, Ni_ULTRA, Co_SULF,
                    Ti_ILM, Ti_RUT,
                    Sn_CASS, W_SCHE, W_WOLF,
                    Au_QUAR, Ag_POLY, PGM_NI,
                    REE_MON, REE_BAST, REE_APAT,
                    U_URAN, Th_MON, S_PYR, S_GAS, F_FLUOR,
                    Si_QUAR -> new DepositProfile(DistributionKind.COMPACT, 1, 3);

            default -> new DepositProfile(DistributionKind.COMPACT, 1, 3);
        };
    }

    private static class DepositTile {
        final Tile tile;
        final int dist;
        final Tile prev;

        DepositTile(Tile tile, int dist) {
            this.tile = tile;
            this.dist = dist;
            this.prev = null;
        }

        DepositTile(Tile tile, int dist, Tile prev) {
            this.tile = tile;
            this.dist = dist;
            this.prev = prev;
        }
    }

    private static class DepositProfile {
        final DistributionKind kind;
        final int minHex;
        final int maxHex;

        DepositProfile(DistributionKind kind, int minHex, int maxHex) {
            this.kind = kind;
            this.minHex = minHex;
            this.maxHex = maxHex;
        }
    }

    private enum DistributionKind {
        COMPACT,
        CLUSTER,
        BELT,
        BASIN,
        LINE,
        PATCHY
    }

    private List<Tile> lineCandidates(Tile current, Tile prev, boolean[] used, Tile seed, int n, List<Tile> tiles) {
        List<Tile> candidates = new ArrayList<>();
        for (Tile nb : current.neighbors) {
            if (nb == null || nb.id < 0 || nb.id >= n) continue;
            if (used[nb.id]) continue;
            if (!depositSuitability(DistributionKind.LINE, nb, seed)) continue;
            candidates.add(nb);
        }
        if (current.isRiver) {
            int to = current.riverTo;
            if (to >= 0 && to < n && !used[to]) {
                Tile down = tiles.get(to);
                if (down != null && !candidates.contains(down)) candidates.add(0, down);
            }
        }
        return candidates;
    }

    private Tile pickWithDirectionBias(List<Tile> candidates, Tile current, Tile prev, Random rnd) {
        if (candidates.isEmpty()) return null;
        if (prev == null || candidates.size() == 1) return candidates.get(rnd.nextInt(candidates.size()));
        double dx = current.lon - prev.lon;
        double dy = current.lat - prev.lat;
        double bestScore = -1e9;
        Tile best = candidates.get(0);
        for (Tile c : candidates) {
            double vx = c.lon - current.lon;
            double vy = c.lat - current.lat;
            double dot = dx * vx + dy * vy;
            double score = dot + rnd.nextGaussian() * 0.05;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private Tile pickAlternate(List<Tile> candidates, Tile picked, Tile current, Tile prev, Random rnd) {
        if (candidates.size() <= 1) return null;
        List<Tile> filtered = new ArrayList<>();
        for (Tile c : candidates) {
            if (c != picked) filtered.add(c);
        }
        return pickWithDirectionBias(filtered, current, prev, rnd);
    }

    private int[] qualityRange(ResourceType type) {
        return switch (type) {
            // high-quality magmatic/hydrothermal
            case Cr_MASS, Ni_ULTRA, PGM_NI, Ti_ILM, Fe_TMAG,
                    Cu_PORP, Cu_SKAR, Cu_VMS, Mo_PORP, W_WOLF, W_SCHE, Sn_CASS,
                    Au_QUAR, Ag_POLY -> new int[]{55, 90};

            // sedimentary/chemical/low-temp
            case Fe_BIF, Fe_OOL, Mn_CARB, ZnPb_SEDEX, ZnPb_MVT, P_PHOS, S_PYR, U_SAND, Fe_META -> new int[]{35, 80};

            // laterites / weathering profiles
            case Ni_LAT_L, Ni_LAT_S, Co_LATER, Al_BOX_L, Al_BOX_K -> new int[]{25, 70};

            // placers / concentrates
            case Au_PLAC, Sn_PLAC, Ti_SAND, PGM_PLAC, Zr_CIRC, Fe_SAND -> new int[]{60, 95};

            // clays / adsorption / brines
            case REE_ION, Li_BRINE, Mg_BRINE, Br_BRINE, I_BRINE, Na_BRINE, Ca_BRINE, B_BORAT -> new int[]{20, 60};

            // energy
            case WIND_PWR, SOLAR_PWR, HYDRO_PWR, TIDAL_PWR, GEO_HEAT -> new int[]{40, 95};

            // water/atmosphere
            case H2O_FRESH, H2O_SALT -> new int[]{80, 100};
            case ATM_AIR, ATM_CO2, ATM_H2S, ATM_NH3 -> new int[]{40, 95};

            // hydrocarbons
            case HC_OIL_L, HC_OIL_H, HC_BITUM, HC_GAS, HC_COND, HC_SHALE -> new int[]{40, 90};

            // uranium/thorium/REE hardrock
            case REE_MON, REE_BAST, REE_APAT, U_URAN, U_PHOS, Th_MON -> new int[]{45, 85};

            // common metals / other
            case Fe_HEM, Fe_MAG, Fe_SID, Fe_LIM, Fe_GOE,
                    Mn_PYR, Mn_FERR,
                    Cr_PLAC,
                    Cu_CHAL, Cu_BORN, Cu_CHZ, Cu_MAL,
                    ZnPb_SULF, ZnPb_CARB, ZnPb_OX,
                    Ni_SULF,
                    Co_SULF,
                    Al_NEPH, Al_ALUN,
                    Ti_RUT,
                    S_GAS, F_FLUOR,
                    Si_QUAR, Si_SAND,
                    C_COAL, C_LIGN, C_GRAPH,
                    Na_SALT, K_SALT,
                    H2O_ICE_RES, CH4_ICE_RES, NH3_ICE_RES, CO2_ICE_RES, CRYO_VOLATILES,
                    FERTILITY, FERTILITY_NAT, AGRO_ZONE, BIO_MAT,
                    IMPACT_GLASS -> new int[]{35, 85};

            default -> new int[]{30, 85};
        };
    }

    private double qualityModifiers(Tile t, ResourceType type, ResourceLayer layer, double score, PlanetConfig planet, WorldType worldType) {
        double mod = 0.0;
        double moist = weightedSoilMoisture(t);
        double temp = weightedTemp(t);
        double boundary = plateBoundaryScore(t);

        // климат: влажно+тепло ухудшает сульфиды и большинство руд
        if (isMetalOre(type) || isHydrocarbon(type)) {
            double wetWarm = clamp01((moist - 60) / 40.0) * clamp01((temp - 20) / 25.0);
            mod -= 10.0 * wetWarm;
            double dry = clamp01((25 - moist) / 25.0);
            mod += 8.0 * dry;
        }

        // магматизм повышает качество магматических и гидротермальных руд
        if (isMagmatic(type)) {
            double magScore = clamp01(t.volcanism / 100.0) * 0.6 + clamp01(t.tectonicStress / 100.0) * 0.4;
            if (boundary > 0) magScore = clamp01(magScore + 0.2 * boundary);
            mod += 5.0 + 10.0 * magScore;
        }

        // границы плит ухудшают осадочные
        if (isSedimentary(type) && boundary > 0.3) {
            mod -= 4.0 * boundary;
        }

        // россыпи улучшаются при наличии воды/реки
        if (isPlacer(type)) {
            double placerBoost = 0.2 + (t.isRiver ? 0.4 : 0.0) + clamp01(t.riverFlow);
            mod += 10.0 * clamp01(placerBoost);
        }

        // латериты любят жару и влагу, но качество падает в переувлажнении
        if (isLaterite(type)) {
            double lat = clamp01((temp - 25) / 25.0) * clamp01(moist / 100.0);
            mod += 8.0 * lat;
            if (moist > 85) mod -= 5.0;
        }

        // испариты/рассолы: лучше в сухих жарких местах
        if (isBrineOrEvaporite(type)) {
            double evap = clamp01((30 - moist) / 30.0) * clamp01((temp - 15) / 25.0);
            mod += 10.0 * evap;
        }

        // лёд ухудшает доступность и качество поверхностных руд
        if (isMetalOre(type) && (worldType == WorldType.ICE_VOLATILE || worldType == WorldType.ICE_ROCKY)) {
            double iceKm = planet.subsurfaceIceThicknessMeters / 1000.0;
            double icePenalty = clamp01(iceKm / 3.0);
            if (layer == ResourceLayer.SURFACE) mod -= 12.0 * icePenalty;
            else if (layer == ResourceLayer.DEEP) mod -= 6.0 * icePenalty;
        }
        if (t.ice) mod -= 4.0;

        // твёрдость пород
        mod += (t.rockHardness - 0.5) * 20.0;

        // качество слегка коррелирует со score
        mod += (score - 0.5) * 6.0;

        return mod;
    }

    private double[] layerWeights(ResourceType type) {
        return switch (type) {
            // hydrocarbons (surface present but mostly deep)
            case HC_OIL_L -> new double[]{0.2, 0.7, 0.1};
            case HC_OIL_H -> new double[]{0.3, 0.6, 0.1};
            case HC_GAS -> new double[]{0.1, 0.7, 0.2};
            case HC_COND -> new double[]{0.1, 0.8, 0.1};
            case HC_SHALE -> new double[]{0.4, 0.6, 0.0};
            case HC_BITUM -> new double[]{0.8, 0.2, 0.0};
            case C_COAL, C_LIGN -> new double[]{0.9, 0.1, 0.0};

            // epithermal Au/Ag (shallow to mid-depth)
            case Au_QUAR, Ag_POLY -> new double[]{0.6, 0.4, 0.0};

            // porphyry (deep to very deep)
            case Cu_PORP, Mo_PORP -> new double[]{0.1, 0.7, 0.2};

            // skarns (shallow-deep with some very deep)
            case Cu_SKAR, W_SCHE, W_WOLF, Sn_CASS -> new double[]{0.3, 0.5, 0.2};

            // VMS (seafloor to buried)
            case Cu_VMS, ZnPb_SULF -> new double[]{0.5, 0.5, 0.0};

            // laterites (near-surface)
            case Ni_LAT_L, Ni_LAT_S, Co_LATER, Al_BOX_L, Al_BOX_K -> new double[]{1.0, 0.0, 0.0};

            // placers (surface)
            case Au_PLAC, Sn_PLAC, Ti_SAND, PGM_PLAC, Zr_CIRC, Fe_SAND -> new double[]{1.0, 0.0, 0.0};

            // brines / evaporites (surface to shallow)
            case Na_SALT, K_SALT, Li_BRINE, Mg_BRINE, Br_BRINE, I_BRINE, Na_BRINE, Ca_BRINE, B_BORAT -> new double[]{0.8, 0.2, 0.0};

            default -> null;
        };
    }

    private double layerQualityAdjust(ResourceType type, ResourceLayer layer) {
        if (!isHydrocarbon(type)) return 0.0;
        return switch (layer) {
            case SURFACE -> -6.0;
            case DEEP -> 0.0;
            case VERY_DEEP -> 3.0;
        };
    }

    private double layerSaturationAdjust(ResourceType type, ResourceLayer layer) {
        if (!isHydrocarbon(type)) return 1.0;
        return switch (layer) {
            case SURFACE -> 0.7;
            case DEEP -> 1.0;
            case VERY_DEEP -> 1.1;
        };
    }

    private int applyAmountJitter(int amount, Random rnd) {
        if (amount <= 1) return amount;
        int maxSwing = Math.max(2, Math.min(amount - 1, 100 - amount));
        int delta = (int) Math.round(rnd.nextGaussian() * 0.35 * maxSwing);
        int v = amount + delta;
        if (v < 1) v = 1;
        if (v > 100) v = 100;
        return v;
    }

    private double computeLogTonnes(ResourceType type, ResourceLayer layer, int amount, int quality, int saturation, Random rnd) {
        double[] range = referenceLogRange(type);
        if (range == null) return 0.0;
        double min = range[0];
        double max = range[1];
        double center = (min + max) * 0.5;
        double sigma = Math.max(0.25, (max - min) / 5.0);

        double normAmount = amount / 100.0;
        double normQuality = quality / 100.0;
        double normSat = saturation / 100.0;

        double base = min + (max - min) * (0.2 + 0.6 * normAmount);
        base += (normQuality - 0.5) * 0.15 * (max - min);
        base += (normSat - 0.5) * 0.10 * (max - min);

        double noise = rnd.nextGaussian() * sigma;
        double logT = base + noise;
        if (logT < min) logT = min + rnd.nextDouble() * (center - min);
        if (logT > max) logT = max - rnd.nextDouble() * (max - center);
        return logT;
    }

    private double computeTonnes(ResourceType type, double logTonnes) {
        if (logTonnes <= 0) return 0.0;
        return Math.pow(10.0, logTonnes);
    }

    private void mergeOrAddResource(Tile t, ResourcePresence incoming) {
        if (t.resources == null) {
            t.resources = new java.util.ArrayList<>();
        }
        for (ResourcePresence existing : t.resources) {
            if (existing.type == incoming.type && existing.layer == incoming.layer) {
                // merge: keep best quality/saturation/amount, sum tonnes in underlying units
                existing.quality = Math.max(existing.quality, incoming.quality);
                existing.saturation = Math.max(existing.saturation, incoming.saturation);
                existing.amount = Math.max(existing.amount, incoming.amount);

                double unitExisting = unitAmountFromLog(existing.type, existing.logTonnes);
                double unitIncoming = unitAmountFromLog(incoming.type, incoming.logTonnes);
                double unitSum = unitExisting + unitIncoming;
                if (unitSum > 0) {
                    existing.logTonnes = Math.log10(unitSum);
                    existing.tonnes = computeTonnes(existing.type, existing.logTonnes);
                } else {
                    existing.logTonnes = Math.max(existing.logTonnes, incoming.logTonnes);
                    existing.tonnes = Math.max(existing.tonnes, incoming.tonnes);
                }
                return;
            }
        }
        t.resources.add(incoming);
    }

    private double unitAmountFromLog(ResourceType type, double logTonnes) {
        if (logTonnes <= 0) return 0.0;
        return Math.pow(10.0, logTonnes);
    }

    public static double[] referenceLogRange(ResourceType type) {
        return switch (type) {
            // Porphyry Cu/Mo
            case Cu_PORP, Mo_PORP -> new double[]{8.0, 9.5};

            // VMS and skarns
            case Cu_VMS -> new double[]{4.5, 8.5};
            case Cu_SKAR, W_SCHE, W_WOLF, Sn_CASS -> new double[]{5.5, 8.0};

            // MVT / SEDEX / Zn-Pb
            case ZnPb_MVT -> new double[]{5.0, 8.5};
            case ZnPb_SEDEX -> new double[]{7.5, 9.0};
            case ZnPb_SULF, ZnPb_CARB, ZnPb_OX -> new double[]{5.5, 8.5};

            // Epithermal / Au-Ag
            case Au_QUAR, Ag_POLY -> new double[]{5.0, 7.5};

            // Placers
            case Au_PLAC, Sn_PLAC, Ti_SAND, PGM_PLAC, Zr_CIRC, Fe_SAND -> new double[]{5.0, 7.5};

            // Iron formations / BIF / oolitic
            case Fe_BIF, Fe_OOL, Fe_HEM, Fe_MAG, Fe_SID, Fe_LIM, Fe_GOE, Fe_META -> new double[]{7.0, 9.5};
            case Fe_TMAG -> new double[]{6.5, 8.5};

            // Mn, Cr, Ni, Co
            case Mn_PYR, Mn_CARB, Mn_FERR -> new double[]{5.0, 8.0};
            case Cr_MASS, Cr_PLAC -> new double[]{5.0, 8.0};
            case Ni_SULF, Ni_ULTRA -> new double[]{5.0, 8.0};
            case Ni_LAT_L, Ni_LAT_S, Co_LATER -> new double[]{6.4, 8.6};
            case Co_SULF -> new double[]{4.5, 7.5};

            // Al / Ti / Zr / Sn
            case Al_BOX_L, Al_BOX_K -> new double[]{7.0, 9.0};
            case Al_NEPH, Al_ALUN -> new double[]{6.0, 8.0};
            case Ti_ILM, Ti_RUT -> new double[]{5.0, 8.0};

            // REE / U / Th / P
            case REE_MON, REE_BAST, REE_APAT -> new double[]{5.0, 7.5};
            case REE_ION -> new double[]{6.0, 8.5};
            case U_URAN, U_PHOS -> new double[]{4.5, 7.0};
            case U_SAND -> new double[]{5.0, 8.0};
            case Th_MON -> new double[]{4.5, 7.0};
            case P_PHOS -> new double[]{7.0, 9.0};

            // Coal / lignite
            case C_COAL, C_LIGN -> new double[]{9.0, 12.0};

            // Brines / evaporites
            case Na_SALT, K_SALT, Li_BRINE, Mg_BRINE, Br_BRINE, I_BRINE, Na_BRINE, Ca_BRINE, B_BORAT -> new double[]{7.0, 10.0};

            // Hydrocarbons (all in log10 tonnes)
            // Oil-like: log10(barrels) + log10(0.136 t/barrel) => -0.866...
            case HC_OIL_L, HC_OIL_H, HC_COND -> new double[]{6.13, 8.63};
            // Gas: log10(cubic feet) + log10(2.03e-5 t/ft^3) => -4.692...
            case HC_GAS -> new double[]{5.31, 7.81};
            case HC_SHALE, HC_BITUM -> new double[]{6.13, 8.13};

            default -> null;
        };
    }

    private boolean isMagmatic(ResourceType type) {
        return switch (type) {
            case Cr_MASS, Ni_ULTRA, PGM_NI, Ti_ILM, Fe_TMAG,
                    Cu_PORP, Cu_SKAR, Cu_VMS, Mo_PORP, W_WOLF, W_SCHE, Sn_CASS,
                    Au_QUAR, Ag_POLY -> true;
            default -> false;
        };
    }

    private boolean isSedimentary(ResourceType type) {
        return switch (type) {
            case Fe_BIF, Fe_OOL, Mn_CARB, ZnPb_SEDEX, ZnPb_MVT, P_PHOS, S_PYR, U_SAND -> true;
            default -> false;
        };
    }

    private boolean isPlacer(ResourceType type) {
        return switch (type) {
            case Au_PLAC, Sn_PLAC, Ti_SAND, PGM_PLAC, Zr_CIRC, Fe_SAND -> true;
            default -> false;
        };
    }

    private boolean isLaterite(ResourceType type) {
        return switch (type) {
            case Ni_LAT_L, Ni_LAT_S, Co_LATER, Al_BOX_L, Al_BOX_K -> true;
            default -> false;
        };
    }

    private boolean isBrineOrEvaporite(ResourceType type) {
        return switch (type) {
            case Na_SALT, K_SALT, Li_BRINE, Mg_BRINE, Br_BRINE, I_BRINE, Na_BRINE, Ca_BRINE, B_BORAT -> true;
            default -> false;
        };
    }

    private boolean isHydrocarbon(ResourceType type) {
        return switch (type) {
            case HC_OIL_L, HC_OIL_H, HC_BITUM, HC_GAS, HC_COND, HC_SHALE -> true;
            default -> false;
        };
    }

    private double weightedTemp(Tile t) {
        double inter = firstFinite(t.biomeTempInterseason, t.temperature);
        double warm = firstFinite(t.biomeTempWarm, t.tempWarm, inter);
        double cold = firstFinite(t.biomeTempCold, t.tempCold, inter);
        return (warm + 2.0 * inter + cold) / 4.0;
    }

    private double weightedPrecipPhysical(Tile t) {
        double inter = Math.max(0.0, firstFinite(t.precipKgM2DayInterseason, t.precipKgM2Day, t.biomePrecipInterseason, t.precipAvg, 0.0));
        double warm = Math.max(0.0, firstFinite(t.precipKgM2DayWarm, t.biomePrecipWarm, inter));
        double cold = Math.max(0.0, firstFinite(t.precipKgM2DayCold, t.biomePrecipCold, inter));
        return (warm + 2.0 * inter + cold) / 4.0;
    }

    private double weightedEvapPhysical(Tile t) {
        double inter = Math.max(0.0, firstFinite(t.evapKgM2DayInterseason, t.evapKgM2Day, t.biomeEvapInterseason, t.evapAvg, 0.0));
        double warm = Math.max(0.0, firstFinite(t.evapKgM2DayWarm, t.biomeEvapWarm, inter));
        double cold = Math.max(0.0, firstFinite(t.evapKgM2DayCold, t.biomeEvapCold, inter));
        return (warm + 2.0 * inter + cold) / 4.0;
    }

    private double weightedSoilMoisture(Tile t) {
        double inter = firstFinite(t.biomeMoistureInterseason, t.moisture, 0.0);
        double warm = firstFinite(t.biomeMoistureWarm, t.moistureWarm, inter);
        double cold = firstFinite(t.biomeMoistureCold, t.moistureCold, inter);
        return clamp((warm + 2.0 * inter + cold) / 4.0, 0.0, 100.0);
    }

    private double firstFinite(double... values) {
        for (double v : values) {
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }
}
