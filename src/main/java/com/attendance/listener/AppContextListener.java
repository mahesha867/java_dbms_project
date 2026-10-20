package com.attendance.listener;

import com.attendance.util.DBConnection;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.sql.Connection;
import java.sql.Statement;
import java.util.TimeZone;

/**
 * Ensures a consistent UTC timezone strategy and executes startup schema self-healing migrations.
 */
@WebListener
public class AppContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.out.println("[AppContextListener] Application default timezone configured to UTC.");

        // Execute self-healing database migrations on startup
        runStartupMigrations();
    }

    private void runStartupMigrations() {
        System.out.println("[AppContextListener] Running startup schema self-healing migrations...");
        try (Connection conn = DBConnection.getInstance().getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Expand session_token to VARCHAR(255) to accommodate rotating HMAC-SHA256 signatures
            try {
                stmt.executeUpdate("ALTER TABLE qr_sessions MODIFY COLUMN session_token VARCHAR(255) NOT NULL");
                System.out.println("[AppContextListener] Migrated qr_sessions.session_token to VARCHAR(255).");
            } catch (Exception e) {
                System.out.println("[AppContextListener] qr_sessions.session_token migration notice: " + e.getMessage());
            }

            // 2. Expand scan_attempts outcome column if needed
            try {
                stmt.executeUpdate("ALTER TABLE scan_attempts MODIFY COLUMN outcome VARCHAR(50) NOT NULL");
                System.out.println("[AppContextListener] Migrated scan_attempts.outcome to VARCHAR(50).");
            } catch (Exception e) {
                System.out.println("[AppContextListener] scan_attempts.outcome migration notice: " + e.getMessage());
            }

            // 3. Clean up any corrupted legacy 'pending_init' sessions so they never block new sessions
            try {
                stmt.executeUpdate("UPDATE qr_sessions SET session_token = CONCAT('cleaned_', id, '_', UNIX_TIMESTAMP()), is_active = FALSE, closed_at = UTC_TIMESTAMP() WHERE session_token = 'pending_init' OR session_token LIKE 'pending_init%'");
                System.out.println("[AppContextListener] Cleaned up legacy uninitialized sessions.");
            } catch (Exception e) {
                System.out.println("[AppContextListener] Session cleanup notice: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[AppContextListener] Startup migration notice: " + e.getMessage());
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Cleanup if necessary
    }
}

