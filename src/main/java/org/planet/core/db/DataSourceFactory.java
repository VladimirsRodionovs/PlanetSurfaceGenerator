package org.planet.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class DataSourceFactory {

    public static DataSource create(DbConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl);
        hc.setUsername(cfg.user);
        hc.setPassword(cfg.password);
        hc.setMaximumPoolSize(cfg.maxPoolSize);

        // Важно для MySQL
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hc.addDataSourceProperty("useServerPrepStmts", "true");

        return new HikariDataSource(hc);
    }
}
