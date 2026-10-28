package com.vendoryx;

import com.vendoryx.config.DBConnection;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * Local smoke test for DB connectivity only.
 * Real login goes through LoginServlet -> UserDAO.authenticate (DB check).
 *
 * Run: AppServer for APIs, or this class to verify Neon connection.
 */
public class TestDB {

    public static void main(String[] args) {
        System.out.println("Checking Neon PostgreSQL connection...");

        try (Connection conn = DBConnection.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            System.out.println("Connected successfully.");
            System.out.println("Database: " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
            System.out.println("URL: " + meta.getURL());
            System.out.println("Use POST /api/auth/login to authenticate against the users table.");
        } catch (Exception e) {
            System.err.println("Database connection failed.");
            e.printStackTrace();
        }
    }
}
