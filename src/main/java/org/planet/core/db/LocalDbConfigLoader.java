package org.planet.core.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class LocalDbConfigLoader {
    private static final Path DEFAULT_CONFIG_PATH = Paths.get("local", "db.local.properties");

    private LocalDbConfigLoader() {
    }

    public static void apply(DbConfig cfg) {
        Path path = resolvePath();
        if (!Files.exists(path)) {
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read local DB config: " + path.toAbsolutePath(), e);
        }

        cfg.jdbcUrl = pick(props.getProperty("db.url"), cfg.jdbcUrl);
        cfg.user = pick(props.getProperty("db.user"), cfg.user);
        cfg.password = pick(props.getProperty("db.password"), cfg.password);
        cfg.starSystemsTable = pick(props.getProperty("db.table.starsystems"), cfg.starSystemsTable);
        cfg.planetSurfacesTable = pick(props.getProperty("db.table.surfaces"), cfg.planetSurfacesTable);
    }

    private static Path resolvePath() {
        String override = pick(
                System.getProperty("planet.db.config.path"),
                System.getenv("PLANET_DB_CONFIG_PATH")
        );
        if (override == null) {
            return DEFAULT_CONFIG_PATH;
        }
        return Paths.get(override);
    }

    private static String pick(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }
}
