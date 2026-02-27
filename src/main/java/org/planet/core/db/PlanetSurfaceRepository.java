package org.planet.core.db;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.zip.GZIPOutputStream;

public class PlanetSurfaceRepository {

    private final DataSource ds;
    private final String tableName;
    private static final String DEFAULT_TABLE_NAME = DbConfig.DEFAULT_PLANET_SURFACES_TABLE;
    private static final String ENC_GZIP = "gzip";

    public PlanetSurfaceRepository(DataSource ds) {
        this(ds, DEFAULT_TABLE_NAME);
    }

    public PlanetSurfaceRepository(DataSource ds, String tableName) {
        this.ds = ds;
        this.tableName = normalizeTableName(tableName, DEFAULT_TABLE_NAME);
    }

    public static final class UpsertReceipt {
        public final int rowsAffected;
        public final long storedSeed;
        public final int jsonValid;
        public final int jsonLength;
        public final long charLength;

        public UpsertReceipt(int rowsAffected, long storedSeed, int jsonValid, int jsonLength, long charLength) {
            this.rowsAffected = rowsAffected;
            this.storedSeed = storedSeed;
            this.jsonValid = jsonValid;
            this.jsonLength = jsonLength;
            this.charLength = charLength;
        }
    }

    public UpsertReceipt upsertSurface(int starSysIdx,
                                       int planetIdx,
                                       String planetName,
                                       long seed,
                                       String hexDataJson) throws SQLException {
        if (hexDataJson == null || hexDataJson.isEmpty()) {
            throw new SQLException("Upsert failed: empty HexData JSON payload");
        }

        byte[] raw = hexDataJson.getBytes(StandardCharsets.UTF_8);
        byte[] gz = gzip(raw);

        try (Connection c = ds.getConnection()) {
            validateUnifiedSchema(c);
            return upsertBinaryAlt(c, starSysIdx, planetIdx, planetName, seed, raw, gz);
        }
    }

    private UpsertReceipt upsertBinaryAlt(Connection c,
                                          int starSysIdx,
                                          int planetIdx,
                                          String planetName,
                                          long seed,
                                          byte[] raw,
                                          byte[] gz) throws SQLException {
        String sql = """
            INSERT INTO %s (StarSys, PlanetIdx, PlanetName, PlanetSeed, HexDataBin, HexDataUSize, HexDataSizeEnc)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              PlanetName = VALUES(PlanetName),
              PlanetSeed = VALUES(PlanetSeed),
              HexDataBin = VALUES(HexDataBin),
              HexDataUSize = VALUES(HexDataUSize),
              HexDataSizeEnc = VALUES(HexDataSizeEnc)
            """.formatted(tableName);

        String verifySql = """
            SELECT PlanetSeed,
                   COALESCE(HexDataUSize, 0) AS usz,
                   COALESCE(HexDataSizeEnc, '') AS enc,
                   COALESCE(OCTET_LENGTH(HexDataBin), 0) AS bl
            FROM %s
            WHERE StarSys = ? AND PlanetIdx = ?
            """.formatted(tableName);

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, starSysIdx);
            ps.setInt(2, planetIdx);
            ps.setString(3, planetName);
            ps.setLong(4, seed);
            ps.setBytes(5, gz);
            ps.setInt(6, raw.length);
            ps.setString(7, ENC_GZIP);

            int rows = ps.executeUpdate();
            try (PreparedStatement v = c.prepareStatement(verifySql)) {
                v.setInt(1, starSysIdx);
                v.setInt(2, planetIdx);
                try (ResultSet rs = v.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Upsert verification failed: row not found after write");
                    }
                    long storedSeed = rs.getLong("PlanetSeed");
                    int usize = rs.getInt("usz");
                    String enc = rs.getString("enc");
                    long binLen = rs.getLong("bl");
                    int jv = ENC_GZIP.equalsIgnoreCase(enc) ? 1 : 0;

                    if (storedSeed != seed) {
                        throw new SQLException("Upsert verification failed: seed mismatch"
                                + " expected=" + seed + " actual=" + storedSeed);
                    }
                    if (jv != 1) {
                        throw new SQLException("Upsert verification failed: unexpected HexDataSizeEnc='" + enc + "'");
                    }
                    if (binLen <= 0 || usize <= 0) {
                        throw new SQLException("Upsert verification failed: empty HexDataBin"
                                + " binLen=" + binLen + " uncompressed=" + usize);
                    }
                    return new UpsertReceipt(rows, storedSeed, jv, usize, binLen);
                }
            }
        }
    }

    private void validateUnifiedSchema(Connection c) throws SQLException {
        boolean hasHexDataBin = hasColumn(c, "HexDataBin");
        boolean hasHexDataUSize = hasColumn(c, "HexDataUSize");
        boolean hasHexDataSizeEnc = hasColumn(c, "HexDataSizeEnc");
        if (hasHexDataBin && hasHexDataUSize && hasHexDataSizeEnc) {
            return;
        }
        throw new SQLException("Table " + tableName + " has unsupported schema: expected "
                + "(HexDataBin, HexDataUSize, HexDataSizeEnc)");
    }

    private boolean hasColumn(Connection c, String columnName) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        String catalog = c.getCatalog();
        try (ResultSet rs = md.getColumns(catalog, null, tableName, "%")) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (columnName.equalsIgnoreCase(col)) {
                    return true;
                }
            }
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

    private static byte[] gzip(byte[] raw) throws SQLException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
            try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
                gos.write(raw);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Failed to gzip HexData JSON", e);
        }
    }
}
