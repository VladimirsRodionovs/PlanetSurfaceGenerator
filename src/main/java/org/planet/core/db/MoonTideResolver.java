package org.planet.core.db;

import org.planet.core.db.dto.StarSystemObjectRow;
import org.planet.core.model.config.PlanetConfig;

import java.util.ArrayList;
import java.util.List;

public final class MoonTideResolver {

    private static final double EARTH_MOON_MASS_EARTH = 0.0123000371;
    private static final double EARTH_MOON_SEMIMAJOR_AU = 0.00256955529;

    private MoonTideResolver() {
    }

    public static void populateMoonTideSources(PlanetConfig planet,
                                               StarSystemObjectRow hostRow,
                                               StarSystemRepository repo) {
        if (planet == null || hostRow == null || repo == null) return;
        planet.moonTideSources = new ArrayList<>();

        final List<StarSystemObjectRow> objects;
        try {
            objects = repo.listObjects(hostRow.starSysIdx);
        } catch (Exception ex) {
            return;
        }

        for (StarSystemObjectRow row : objects) {
            if (row == null) continue;
            if (row.objectInternalId == hostRow.objectInternalId) continue;
            boolean hostedByPlanet = (row.objectOrbitHost == hostRow.objectInternalId);
            if (!hostedByPlanet) continue;
            boolean moonTyped = (row.objectType == 3) || (row.objectPlanetType == 4);
            if (!moonTyped) continue;

            double moonMassEarth = extractMassEarth(row);
            double axisAu = row.orbitSemimajorAxisAU;
            if (moonMassEarth <= 0.0) continue;
            if (axisAu <= 0.0) {
                axisAu = estimateSemimajorAxisAuFromMeanMotion(hostRow, row, moonMassEarth);
            }
            if (axisAu <= 0.0) continue;

            PlanetConfig.MoonTideSource src = new PlanetConfig.MoonTideSource();
            src.objectInternalId = row.objectInternalId;
            src.objectName = row.objectName;
            src.massEarth = moonMassEarth;
            src.orbitSemimajorAxisAU = axisAu;
            src.orbitInclinationDeg = row.orbitInclinationDeg;
            src.meanMotionPerDay = row.orbitMeanMotionPerDay;
            src.forcingRelativeEarthMoon = earthMoonRelativeForcing(moonMassEarth, axisAu);
            planet.moonTideSources.add(src);
        }
    }

    private static double extractMassEarth(StarSystemObjectRow row) {
        if (row != null && row.objectMassEarth > 0.0 && Double.isFinite(row.objectMassEarth)) {
            return row.objectMassEarth;
        }
        double csvMass = parseMassEarthFromDescription(row == null ? null : row.objectDescription);
        if (csvMass > 0.0) return csvMass;
        double estMass = estimateMassEarthFromGravityAndRadius(row == null ? null : row.objectDescription);
        if (estMass > 0.0) return estMass;
        try {
            PlanetConfig moon = PlanetConfigMapper.fromDescription(row);
            return moon.massEarth;
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private static double parseMassEarthFromDescription(String desc) {
        if (desc == null || desc.isBlank()) return 0.0;
        String s = desc.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        String[] parts = s.split(",", -1);
        if (parts.length < 2) return 0.0;
        String v = parts[1] == null ? "" : parts[1].trim().replace("\"", "");
        if (v.isEmpty()) return 0.0;
        try {
            double m = Double.parseDouble(v);
            return (m > 0.0 && Double.isFinite(m)) ? m : 0.0;
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    // Fallback when direct mass field is missing:
    // g/gEarth = (M/Mearth) / (R/Rearth)^2  => M/Mearth = (g/gEarth) * (R/Rearth)^2.
    private static double estimateMassEarthFromGravityAndRadius(String desc) {
        if (desc == null || desc.isBlank()) return 0.0;
        String s = desc.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        String[] p = s.split(",", -1);
        if (p.length < 6) return 0.0;
        double rEarth = parseDoubleSafe(p[4]);
        double gEarth = parseDoubleSafe(p[5]);
        if (rEarth <= 0.0 || gEarth <= 0.0) return 0.0;
        double m = gEarth * rEarth * rEarth;
        return (m > 0.0 && Double.isFinite(m)) ? m : 0.0;
    }

    private static double parseDoubleSafe(String raw) {
        if (raw == null) return 0.0;
        String v = raw.trim().replace("\"", "");
        if (v.isEmpty()) return 0.0;
        try {
            double d = Double.parseDouble(v);
            return (Double.isFinite(d) ? d : 0.0);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static double earthMoonRelativeForcing(double moonMassEarth, double axisAu) {
        double base = EARTH_MOON_MASS_EARTH / Math.pow(EARTH_MOON_SEMIMAJOR_AU, 3.0);
        double f = moonMassEarth / Math.pow(axisAu, 3.0);
        if (base <= 0.0) return 0.0;
        return f / base;
    }

    private static double estimateSemimajorAxisAuFromMeanMotion(StarSystemObjectRow host,
                                                                 StarSystemObjectRow moon,
                                                                 double moonMassEarth) {
        if (host == null || moon == null) return 0.0;
        if (moon.orbitMeanMotionPerDay <= 0.0) return 0.0;
        double hostMassEarth = extractMassEarth(host);
        if (hostMassEarth <= 0.0) return 0.0;
        double periodDays = 360.0 / moon.orbitMeanMotionPerDay;
        if (periodDays <= 0.0 || !Double.isFinite(periodDays)) return 0.0;
        double periodSec = periodDays * 86_400.0;
        double totalMassKg = (hostMassEarth + Math.max(0.0, moonMassEarth)) * 5.97219e24;
        if (totalMassKg <= 0.0) return 0.0;
        double mu = 6.67430e-11 * totalMassKg;
        double aMeters = Math.cbrt(mu * periodSec * periodSec / (4.0 * Math.PI * Math.PI));
        if (!(aMeters > 0.0) || !Double.isFinite(aMeters)) return 0.0;
        return aMeters / 1.495978707e11;
    }
}
