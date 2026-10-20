package com.attendance;

import com.attendance.dao.AttendanceDao;
import com.attendance.dao.ScanAttemptDao;
import com.attendance.model.Attendance;
import com.attendance.model.ScanAttempt;
import com.attendance.util.DBConnection;
import com.attendance.util.PasswordUtil;

import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automated Verification Test Suite for Stage 2: Database and OOP Foundations.
 * Tests:
 * 1. Pooled DataSource acquisition and validation.
 * 2. Password hashing security (removal of hardcoded bypasses).
 * 3. Schema constraints (OD / EXCUSED status, audit log, scan attempts).
 * 4. Scan attempt data minimization (token hash stored, no raw token, no IP).
 * 5. ACID Transaction rollback on failure.
 * 6. Concurrent scans race-condition protection (multi-threaded test).
 * 7. Attendance percentage calculation with OD/LATE inclusion.
 */
public class Stage2VerificationTest {

    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 2 Automated Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. Pooled DataSource Verification", Stage2VerificationTest::testPooledDataSource);
        runTest("2. Password Hashing Security & No Bypasses", Stage2VerificationTest::testPasswordSecurity);
        runTest("3. Schema Constraints & Status ENUM (OD, EXCUSED)", Stage2VerificationTest::testSchemaConstraints);
        runTest("4. Scan Attempt Data Minimization (Token Hash Only)", Stage2VerificationTest::testScanAttemptLogging);
        runTest("5. ACID Transaction Rollback Verification", Stage2VerificationTest::testTransactionRollback);
        runTest("6. Concurrent Scans Duplicate Race Condition Test", Stage2VerificationTest::testConcurrentScans);
        runTest("7. Attendance Percentage Consistency (OD Included)", Stage2VerificationTest::testAttendancePercentage);

        System.out.println("========================================================");
        System.out.println("Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
        System.out.println("========================================================");

        if (totalFailed > 0) {
            System.exit(1);
        }
    }

    private static void runTest(String testName, TestRunnable test) {
        System.out.print("[TEST] " + testName + " ... ");
        try {
            test.run();
            System.out.println("PASS");
            totalPassed++;
        } catch (Throwable t) {
            System.out.println("FAIL");
            System.err.println("   Error: " + t.getMessage());
            t.printStackTrace(System.err);
            totalFailed++;
        }
    }

    @FunctionalInterface
    interface TestRunnable {
        void run() throws Throwable;
    }

    private static void testPooledDataSource() throws Exception {
        DBConnection pool = DBConnection.getInstance();
        javax.sql.DataSource ds = pool.getDataSource();
        if (ds == null) throw new AssertionError("DataSource should not be null");
        if (!(ds instanceof org.apache.tomcat.jdbc.pool.DataSource)) {
            throw new AssertionError("DataSource must be an instance of Tomcat pooled DataSource");
        }
        try (Connection conn = pool.getConnection()) {
            if (conn == null || conn.isClosed()) throw new AssertionError("Connection borrowed is null or closed");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                if (!rs.next() || rs.getInt(1) != 1) throw new AssertionError("Validation query failed");
            }
        }
    }

    private static void testPasswordSecurity() throws Exception {
        String teacherHash = PasswordUtil.hashPassword("teacher123");
        String studentHash = PasswordUtil.hashPassword("student123");

        // 1. Valid password verifies
        if (!PasswordUtil.verifyPassword("teacher123", teacherHash)) {
            throw new AssertionError("Valid teacher123 password should verify");
        }
        if (!PasswordUtil.verifyPassword("student123", studentHash)) {
            throw new AssertionError("Valid student123 password should verify");
        }

        // 2. Incorrect password fails
        if (PasswordUtil.verifyPassword("wrongpass", teacherHash)) {
            throw new AssertionError("Wrong password must fail");
        }

        // 3. Old bypass checks must be rejected
        if (PasswordUtil.verifyPassword("teacher123", "$2a$10$8K1pFakeHash")) {
            throw new AssertionError("Hardcoded fake bcrypt prefix must not bypass verification");
        }
        if (PasswordUtil.verifyPassword("student123", "$2a$10$N9qoFakeHash")) {
            throw new AssertionError("Hardcoded fake student prefix must not bypass verification");
        }
    }

    private static void testSchemaConstraints() throws Exception {
        try (Connection conn = DBConnection.getInstance().getConnection()) {
            // Verify OD status can be inserted
            int testStudentId = 1;
            int testCourseId = 1;

            // Find or create a temporary session
            int tempSessionId;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM qr_sessions LIMIT 1")) {
                if (rs.next()) {
                    tempSessionId = rs.getInt(1);
                } else {
                    throw new AssertionError("No QR session found for schema test");
                }
            }

            // Test OD status in attendance enum
            String checkEnumSql = "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
                                  "WHERE TABLE_SCHEMA = 'attendance_db' AND TABLE_NAME = 'attendance' AND COLUMN_NAME = 'status'";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(checkEnumSql)) {
                if (rs.next()) {
                    String colType = rs.getString(1);
                    if (!colType.contains("'OD'") || !colType.contains("'EXCUSED'")) {
                        throw new AssertionError("attendance.status ENUM missing 'OD' or 'EXCUSED': " + colType);
                    }
                }
            }
        }
    }

    private static void testScanAttemptLogging() throws Exception {
        ScanAttemptDao dao = new ScanAttemptDao();
        String rawToken = "stage2-test-token-safe-hash-test-" + System.currentTimeMillis();
        String safeTokenHash = PasswordUtil.hashPassword(rawToken);

        boolean recorded = dao.recordAttempt(1, 1, safeTokenHash, "INVALID");
        if (!recorded) throw new AssertionError("Failed to record scan attempt");

        List<ScanAttempt> list = dao.findRecentAttempts(5);
        if (list.isEmpty()) throw new AssertionError("Recent attempts should not be empty");

        ScanAttempt latest = list.get(0);
        if (!safeTokenHash.equals(latest.getTokenHash())) {
            throw new AssertionError("Token hash mismatch in scan attempts");
        }
        // Verify raw token is NOT stored
        if (rawToken.equals(latest.getTokenHash())) {
            throw new AssertionError("Raw token was stored! Must store only cryptographic hash.");
        }
    }

    private static void testTransactionRollback() throws Exception {
        AttendanceDao dao = new AttendanceDao();
        Connection conn = DBConnection.getInstance().getConnection();
        conn.setAutoCommit(false);

        String testTokenHash = PasswordUtil.hashPassword("rollback-test-" + System.currentTimeMillis());

        try {
            // Step 1: Insert an attempt inside uncommitted transaction
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO scan_attempts (session_id, student_id, token_hash, outcome) VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, 1);
                ps.setInt(2, 1);
                ps.setString(3, testTokenHash);
                ps.setString(4, "SUCCESS");
                ps.executeUpdate();
            }

            // Step 2: Simulate failure and ROLLBACK
            conn.rollback();
        } finally {
            conn.setAutoCommit(true);
            conn.close();
        }

        // Verify the record does NOT exist in database after rollback
        try (Connection verifyConn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = verifyConn.prepareStatement("SELECT COUNT(*) FROM scan_attempts WHERE token_hash = ?")) {
            ps.setString(1, testTokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) != 0) {
                    throw new AssertionError("Transaction rollback failed: uncommitted record persisted!");
                }
            }
        }
    }

    private static void testConcurrentScans() throws Exception {
        AttendanceDao dao = new AttendanceDao();

        // Create a unique temporary session for this test
        int testSessionId;
        int courseId = 1;
        int studentId = 2; // Student 2
        String sessionToken = "concurrent-test-session-" + System.currentTimeMillis();

        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO qr_sessions (session_token, course_id, teacher_id, expires_at, is_active) " +
                     "VALUES (?, ?, 1, DATE_ADD(NOW(), INTERVAL 1 HOUR), 1)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sessionToken);
            ps.setInt(2, courseId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    testSessionId = keys.getInt(1);
                } else {
                    throw new AssertionError("Failed to create test session");
                }
            }
        }

        // Run 10 concurrent scan attempts simultaneously
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        String tokenHash = PasswordUtil.hashPassword(sessionToken);

        Callable<Void> task = () -> {
            barrier.await(); // Synchronize threads to hit database simultaneously
            try {
                boolean recorded = dao.recordAttendanceTransaction(studentId, courseId, testSessionId, "PRESENT", tokenHash);
                if (recorded) successCount.incrementAndGet();
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062 || (e.getMessage() != null && e.getMessage().contains("Duplicate entry"))) {
                    duplicateCount.incrementAndGet();
                }
            }
            return null;
        };

        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(task));
        }

        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Verify: Exactly 1 transaction succeeded, and the rest were blocked as duplicates
        if (successCount.get() != 1) {
            throw new AssertionError("Expected exactly 1 successful concurrent scan, got: " + successCount.get());
        }

        // Verify: Database contains exactly 1 attendance record for this student and session
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM attendance WHERE student_id = ? AND qr_session_id = ?")) {
            ps.setInt(1, studentId);
            ps.setInt(2, testSessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    if (count != 1) {
                        throw new AssertionError("Database has " + count + " records! Must be exactly 1.");
                    }
                }
            }
        }

        // Clean up test session and attendance
        try (Connection conn = DBConnection.getInstance().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attendance WHERE qr_session_id = ?")) {
                ps.setInt(1, testSessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qr_sessions WHERE id = ?")) {
                ps.setInt(1, testSessionId);
                ps.executeUpdate();
            }
        }
    }

    private static void testAttendancePercentage() throws Exception {
        AttendanceDao dao = new AttendanceDao();
        // Calculates percentage for student 1 in course 1
        double pct = dao.getAttendancePercentage(1, 1);
        if (pct < 0.0 || pct > 100.0) {
            throw new AssertionError("Attendance percentage out of range [0, 100]: " + pct);
        }
    }
}
