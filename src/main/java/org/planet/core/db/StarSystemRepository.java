package org.planet.core.db;

import org.planet.core.db.dto.StarSystemObjectRow;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class StarSystemRepository {

    private final DataSource ds;
    private final String tableName;
    private static final String DEFAULT_TABLE_NAME = DbConfig.DEFAULT_STAR_SYSTEMS_TABLE;

    public StarSystemRepository(DataSource ds) {
        this(ds, DEFAULT_TABLE_NAME);
    }

    public StarSystemRepository(DataSource ds, String tableName) {
        this.ds = ds;
        this.tableName = normalizeTableName(tableName, DEFAULT_TABLE_NAME);
    }

    public int updateObjectDescription(int starSysIdx, int objectInternalId, String objectDescription) throws SQLException {
        String sql = "UPDATE " + tableName + " SET ObjectDescription = ? WHERE StarSystemID = ? AND ObjectInternalID = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, objectDescription);
            ps.setInt(2, starSysIdx);
            ps.setInt(3, objectInternalId);
            return ps.executeUpdate();
        }
    }

    // список объектов, которые нужно генерировать (ObjectPlanetType in (2,4))
    public List<StarSystemObjectRow> listCandidates(int starSysIdx) throws SQLException {
        String sql = "SELECT * " +
                "FROM " + tableName + " " +
                "WHERE StarSystemID = ? AND ObjectPlanetType IN (2,4) " +
                "ORDER BY ObjectInternalID";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, starSysIdx);
            try (ResultSet rs = ps.executeQuery()) {

                List<StarSystemObjectRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(starSysIdx, rs));
                }
                return out;
            }
        }
    }

    // Полный список объектов системы (нужно для расчёта спутников и приливов).
    public List<StarSystemObjectRow> listObjects(int starSysIdx) throws SQLException {
        String sql = "SELECT * FROM " + tableName + " WHERE StarSystemID = ? ORDER BY ObjectInternalID";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, starSysIdx);
            try (ResultSet rs = ps.executeQuery()) {

                List<StarSystemObjectRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(starSysIdx, rs));
                }
                return out;
            }
        }
    }

    // загрузка одной строки (тут позже вытащим все параметры)
    public StarSystemObjectRow loadObjectRow(int starSysIdx, int objectInternalId) throws SQLException {
        String sql = "SELECT * FROM " + tableName + " WHERE StarSystemID = ? AND ObjectInternalID = ? LIMIT 1";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setInt(1, starSysIdx);
            ps.setInt(2, objectInternalId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                return mapRow(starSysIdx, rs);
            }
        }
    }

    private StarSystemObjectRow mapRow(int starSysIdx, ResultSet rs) throws SQLException {
        StarSystemObjectRow r = new StarSystemObjectRow();
        r.starSysIdx = starSysIdx;
        r.objectInternalId = rs.getInt("ObjectInternalID");
        r.objectType = (int) getDoubleIfPresent(rs, "ObjectType");
        r.objectPlanetType = (int) getDoubleIfPresent(rs, "ObjectPlanetType");
        r.objectOrbitHost = (int) getDoubleIfPresent(rs, "ObjectOrbitHost");
        r.orbitSemimajorAxisAU = getDoubleIfPresent(rs, "ObjectOrbitSemimajorAxisAU");
        r.orbitInclinationDeg = getDoubleIfPresent(rs, "ObjectOrbitInclination");
        r.objectMassEarth = firstPositive(
                getDoubleIfPresent(rs, "ObjectMassEarth"),
                getDoubleIfPresent(rs, "ObjectMassME"),
                getDoubleIfPresent(rs, "ObjectMass"),
                getDoubleIfPresent(rs, "MassEarth")
        );
        r.objectName = getStringIfPresent(rs, "ObjectName");
        r.objectDescription = getStringIfPresent(rs, "ObjectDescription");
        r.orbitMeanMotionPerDay = getDoubleIfPresent(rs, "ObjectOrbitMeanMotionPerDay");
        r.axialTiltDeg = getDoubleIfPresent(rs, "ObjectRotationInclination");
        r.rotationPeriodHours = getDoubleIfPresent(rs, "ObjectRotationSpeedSideric");
        r.rotationSpeed = getDoubleIfPresent(rs, "ObjectRotationSpeed");
        r.rotationPrograde = (int) getDoubleIfPresent(rs, "ObjectProGrade");
        return r;
    }

    private double firstPositive(double... vals) {
        if (vals == null) return 0.0;
        for (double v : vals) {
            if (v > 0.0 && Double.isFinite(v)) return v;
        }
        return 0.0;
    }

    private String getStringIfPresent(ResultSet rs, String col) throws SQLException {
        if (!hasColumn(rs, col)) return null;
        return rs.getString(col);
    }

    private double getDoubleIfPresent(ResultSet rs, String col) throws SQLException {
        if (!hasColumn(rs, col)) return 0.0;
        double v = rs.getDouble(col);
        return rs.wasNull() ? 0.0 : v;
    }

    private boolean hasColumn(ResultSet rs, String col) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        for (int i = 1; i <= n; i++) {
            if (col.equalsIgnoreCase(md.getColumnName(i))) return true;
        }
        return false;
    }

    private static String normalizeTableName(String candidate, String fallback) {
        String value = (candidate == null || candidate.isBlank()) ? fallback : candidate.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + value);
        }
        return value;
    }
}
