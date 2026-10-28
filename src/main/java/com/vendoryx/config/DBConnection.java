package com.vendoryx.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP-backed connection factory.
 * Env: DB_URL / DB_USER / DB_PASSWORD (aliases: VENDORYX_DB_*).
 */
public final class DBConnection {

    private static volatile HikariDataSource dataSource;

    private DBConnection() {}

    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    private static HikariDataSource getDataSource() {
        if (dataSource == null) {
            synchronized (DBConnection.class) {
                if (dataSource == null) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(Env.dbUrl());
                    config.setUsername(Env.dbUser());
                    config.setPassword(Env.dbPassword());
                    config.setMaximumPoolSize(5);
                    config.setPoolName("vendoryx-pool");
                    config.setDriverClassName("org.postgresql.Driver");
                    dataSource = new HikariDataSource(config);
                }
            }
        }
        return dataSource;
    }
}
