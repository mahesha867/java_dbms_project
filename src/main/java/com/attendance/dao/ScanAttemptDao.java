package com.attendance.dao;

import com.attendance.model.ScanAttempt;
import com.attendance.util.DBConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for recording and querying scan attempts.
 * Ensures data minimization: stores token SHA-256 hash, no raw reusable secrets, no IP data.
 */
public class ScanAttemptDao {

    /**
     * Records a scan attempt within an existing database connection (for transactional workflows).
     */
    public boolean recordAttempt(Connection conn, Integer sessionId, Integer studentId, String tokenHash, String outcome) throws SQLException {
        String sql = "INSERT INTO scan_attempts (session_id, student_id, token_hash, outcome) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (sessionId != null) ps.setInt(1, sessionId);
            else ps.setNull(1, Types.INTEGER);

            if (studentId != null) ps.setInt(2, studentId);
            else ps.setNull(2, Types.INTEGER);

            ps.setString(3, tokenHash);
            ps.setString(4, outcome);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Standalone attempt recorder with its own borrowed pooled connection.
     */
    public boolean recordAttempt(Integer sessionId, Integer studentId, String tokenHash, String outcome) {
        try (Connection conn = DBConnection.getInstance().getConnection()) {
            return recordAttempt(conn, sessionId, studentId, tokenHash, outcome);
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Retrieves recent scan attempts for audit and suspicious pattern analysis.
     */
    public List<ScanAttempt> findRecentAttempts(int limit) {
        List<ScanAttempt> list = new ArrayList<>();
        String sql = "SELECT * FROM scan_attempts ORDER BY attempted_at DESC LIMIT ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScanAttempt sa = new ScanAttempt();
                    sa.setId(rs.getInt("id"));
                    int sId = rs.getInt("session_id");
                    sa.setSessionId(rs.wasNull() ? null : sId);
                    int stId = rs.getInt("student_id");
                    sa.setStudentId(rs.wasNull() ? null : stId);
                    sa.setTokenHash(rs.getString("token_hash"));
                    sa.setOutcome(rs.getString("outcome"));
                    Timestamp ts = rs.getTimestamp("attempted_at");
                    if (ts != null) sa.setAttemptedAt(ts.toLocalDateTime());
                    list.add(sa);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public boolean hasSuccessfulScan(String tokenHash) {
        String sql = "SELECT 1 FROM scan_attempts WHERE token_hash = ? AND outcome = 'SUCCESS' LIMIT 1";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Counts recent failed scan attempts by a student within a given number of seconds.
     * Used for rate-limiting and rapid guessing / brute-force protection.
     */
    public int countRecentFailedAttempts(int studentId, int secondsWindow) {
        String sql = "SELECT COUNT(*) FROM scan_attempts " +
                     "WHERE student_id = ? AND outcome IN ('INVALID', 'EXPIRED', 'REPLAYED', 'RATE_LIMITED') " +
                     "AND attempted_at >= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP)";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, secondsWindow);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }
}
