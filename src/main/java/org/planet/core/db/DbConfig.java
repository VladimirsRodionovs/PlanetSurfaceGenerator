package org.planet.core.db;

public class DbConfig {
    public static final String DEFAULT_STAR_SYSTEMS_TABLE = "StarSystems";
    public static final String DEFAULT_PLANET_SURFACES_TABLE = "PlanetsSurfaces2";

    public String jdbcUrl;
    public String user;
    public String password;
    public String starSystemsTable = DEFAULT_STAR_SYSTEMS_TABLE;
    public String planetSurfacesTable = DEFAULT_PLANET_SURFACES_TABLE;

    public int maxPoolSize = 10;

    public void applyOverridesFromSystem() {
        jdbcUrl = pick(
                System.getProperty("planet.db.url"),
                System.getenv("PLANET_DB_URL"),
                jdbcUrl
        );
        user = pick(
                System.getProperty("planet.db.user"),
                System.getenv("PLANET_DB_USER"),
                user
        );
        password = pick(
                System.getProperty("planet.db.password"),
                System.getenv("PLANET_DB_PASSWORD"),
                password
        );
        starSystemsTable = pick(
                System.getProperty("planet.db.table.starsystems"),
                System.getenv("PLANET_DB_TABLE_STARSYSTEMS"),
                starSystemsTable
        );
        planetSurfacesTable = pick(
                System.getProperty("planet.db.table.surfaces"),
                System.getenv("PLANET_DB_TABLE_SURFACES"),
                planetSurfacesTable
        );
    }

    private static String pick(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }
}
