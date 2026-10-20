package com.attendance;

import com.attendance.dao.AttendanceDao;
import com.attendance.dao.CourseDao;
import com.attendance.dao.QrSessionDao;
import com.attendance.dao.ScanAttemptDao;
import com.attendance.dao.StudentDao;
import com.attendance.model.Attendance;
import com.attendance.model.Course;
import com.attendance.model.QrSession;
import com.attendance.model.Student;
import com.attendance.util.DBConnection;
import com.attendance.util.PasswordUtil;
import com.attendance.util.QrCodeUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automated Verification Test Suite for Stage 6: Live QR Attendance Flow:
 * 1. Valid scan with server-signed HMAC token within 5-second rotation window.
 * 2. Expired scan (older than validity window rejected with EXPIRED).
 * 3. Replayed scan (already consumed or rotated token rejected with REPLAYED).
 * 4. Duplicate scan (same student scanning twice for same session rejected with DUPLICATE).
 * 5. Wrong-course scan (student not enrolled rejected with WRONG_COURSE).
 * 6. Closed-session scan (session closed rejected with CLOSED_SESSION).
 * 7. Auto-close and absent marking (unmarked enrolled students marked ABSENT upon closure).
 * 8. Simultaneous scans (concurrent threads handled safely via ACID transactions and duplicate conflict rollback).
 * 9. Transaction behavior & rollback integrity.
 * 10. Typed fallback validation (manual input validates same token identical to camera scan).
 */
public class Stage6VerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 6 Automated Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. Valid Scan with Server-Signed HMAC Token (HTTP 200 / PRESENT)", Stage6VerificationTest::testValidScan);
        runTest("2. Expired Token Scan Rejected (EXPIRED in scan_attempts)", Stage6VerificationTest::testExpiredScan);
        runTest("3. Replayed Token Scan Rejected (REPLAYED in scan_attempts)", Stage6VerificationTest::testReplayedScan);
        runTest("4. Duplicate Scan for Same Session Rejected (DUPLICATE)", Stage6VerificationTest::testDuplicateScan);
        runTest("5. Wrong-Course Scan Rejected (WRONG_COURSE)", Stage6VerificationTest::testWrongCourseScan);
        runTest("6. Closed-Session Scan Rejected (CLOSED_SESSION)", Stage6VerificationTest::testClosedSessionScan);
        runTest("7. Auto-Close & Absent Marking for Unmarked Students", Stage6VerificationTest::testAutoCloseAndAbsentMarking);
        runTest("8. Simultaneous Scans & Race Condition Concurrency", Stage6VerificationTest::testSimultaneousScans);
        runTest("9. Transaction Behavior & Rollback Integrity", Stage6VerificationTest::testTransactionRollback);
        runTest("10. Typed Fallback Token Validation Identical to Camera", Stage6VerificationTest::testTypedFallbackValidation);
        runTest("11. Multi-Student Active QR Scan & Per-Student Duplicate Rejection", Stage6VerificationTest::testMultiStudentActiveQrAndDuplicateRejection);

        System.out.println("========================================================");
        System.out.println("Stage 6 Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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

    // ─────────────────────────────────────────────────────────────
    // TEST 1: Valid Scan
    // ─────────────────────────────────────────────────────────────
    private static void testValidScan() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        // Ensure Course 1 exists and Student 1 (aarav@student.com, id 1) is enrolled
        courseDao.enrollStudent(1, 1);

        // Create clean active test session
        QrSession session = createTestSession(1, 1);

        // Generate cryptographically signed token within 5s window
        String validToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), validToken);

        // Student 1 logs in and scans
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        String responseBody = sendPostScan(cmStudent, validToken);
        if (!responseBody.contains("\"success\":true")) {
            throw new AssertionError("Valid scan expected success: true, got: " + responseBody);
        }

        // Verify database persistence
        Optional<Attendance> record = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (!record.isPresent() || !"PRESENT".equals(record.get().getStatus())) {
            throw new AssertionError("Attendance record not found or not PRESENT in DB");
        }

        // Verify scan_attempts has SUCCESS outcome
        verifyScanAttemptOutcome(session.getId(), 1, "SUCCESS");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 2: Expired Scan
    // ─────────────────────────────────────────────────────────────
    private static void testExpiredScan() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        // Student 2 (priya@student.com, id 2) enrolled in Course 1
        courseDao.enrollStudent(2, 1);

        QrSession session = createTestSession(1, 1);

        // Generate expired signed token (timestamp 15s in the past)
        String expiredToken = QrCodeUtil.generateExpiredToken(session.getId(), 1);

        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "priya@student.com", "student123");

        String responseBody = sendPostScan(cmStudent, expiredToken);
        if (!responseBody.contains("\"success\":false") || !responseBody.contains("expired")) {
            throw new AssertionError("Expired scan should return success: false with 'expired', got: " + responseBody);
        }

        // Verify NO attendance record created
        Optional<Attendance> record = attendanceDao.findBySessionAndStudent(session.getId(), 2);
        if (record.isPresent()) {
            throw new AssertionError("Attendance record must not exist for expired scan");
        }

        verifyScanAttemptOutcome(session.getId(), 2, "EXPIRED");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 3: Replayed Scan
    // ─────────────────────────────────────────────────────────────
    private static void testReplayedScan() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();

        // Enroll Student 1 and Student 2 in Course 1
        courseDao.enrollStudent(1, 1);
        courseDao.enrollStudent(2, 1);

        QrSession session = createTestSession(1, 1);

        // 1. Generate Token T1 and set as active
        String token1 = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), token1);

        // Student 1 uses Token T1 successfully
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        sendPostScan(cmStudent1, token1);

        // 2. Rotate token to Token T2 (simulating 5s rotation)
        String token2 = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), token2);

        // 3. Student 2 attempts to replay old Token T1
        CookieManager cmStudent2 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent2, "priya@student.com", "student123");

        String responseBody = sendPostScan(cmStudent2, token1);
        if (!responseBody.contains("\"success\":false") || !responseBody.contains("replayed")) {
            throw new AssertionError("Replaying an already used/rotated token should be rejected with 'replayed', got: " + responseBody);
        }

        verifyScanAttemptOutcome(session.getId(), 2, "REPLAYED");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 4: Duplicate Scan (Same Student)
    // ─────────────────────────────────────────────────────────────
    private static void testDuplicateScan() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();

        courseDao.enrollStudent(1, 1);

        QrSession session = createTestSession(1, 1);

        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);

        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");

        // Scan 1: Success
        String first = sendPostScan(cmStudent1, activeToken);
        if (!first.contains("\"success\":true")) throw new AssertionError("First scan failed");

        // Scan 2: Duplicate attempt by same student
        String second = sendPostScan(cmStudent1, activeToken);
        if (!second.contains("already marked")) {
            throw new AssertionError("Duplicate scan should state 'already marked', got: " + second);
        }

        verifyScanAttemptOutcome(session.getId(), 1, "DUPLICATE");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 5: Wrong-Course Scan
    // ─────────────────────────────────────────────────────────────
    private static void testWrongCourseScan() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();

        // Student 3 (rahul@student.com, id 3) drops Course 1 to ensure not enrolled
        courseDao.dropStudent(3, 1);

        QrSession session = createTestSession(1, 1);

        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);

        CookieManager cmStudent3 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent3, "rahul@student.com", "student123");

        String responseBody = sendPostScan(cmStudent3, activeToken);
        if (!responseBody.contains("\"success\":false") || !responseBody.contains("not enrolled")) {
            throw new AssertionError("Non-enrolled student scan must be rejected with 'not enrolled', got: " + responseBody);
        }

        verifyScanAttemptOutcome(session.getId(), 3, "WRONG_COURSE");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 6: Closed-Session Scan
    // ─────────────────────────────────────────────────────────────
    private static void testClosedSessionScan() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();

        courseDao.enrollStudent(1, 1);

        QrSession session = createTestSession(1, 1);

        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);

        // Explicitly close the session
        sessionDao.closeSession(session.getId());

        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");

        String responseBody = sendPostScan(cmStudent1, activeToken);
        if (!responseBody.contains("\"success\":false") || !responseBody.contains("closed")) {
            throw new AssertionError("Scanning closed session must return 'closed', got: " + responseBody);
        }

        verifyScanAttemptOutcome(session.getId(), 1, "CLOSED_SESSION");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 7: Auto-Close & Absent Marking
    // ─────────────────────────────────────────────────────────────
    private static void testAutoCloseAndAbsentMarking() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        // Enroll Student 1, 2, 3 in Course 1
        courseDao.enrollStudent(1, 1);
        courseDao.enrollStudent(2, 1);
        courseDao.enrollStudent(3, 1);

        QrSession session = createTestSession(1, 1);

        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);

        // Student 1 attends
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        sendPostScan(cmStudent1, activeToken);

        // Student 2 and Student 3 do NOT scan. Now close session!
        sessionDao.closeSession(session.getId());

        // Verify Student 1 is PRESENT
        Optional<Attendance> s1 = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (!s1.isPresent() || !"PRESENT".equals(s1.get().getStatus())) {
            throw new AssertionError("Student 1 should be PRESENT");
        }

        // Verify Student 2 and Student 3 are automatically marked ABSENT
        Optional<Attendance> s2 = attendanceDao.findBySessionAndStudent(session.getId(), 2);
        if (!s2.isPresent() || !"ABSENT".equals(s2.get().getStatus())) {
            throw new AssertionError("Student 2 should be marked ABSENT upon session closure");
        }

        Optional<Attendance> s3 = attendanceDao.findBySessionAndStudent(session.getId(), 3);
        if (!s3.isPresent() || !"ABSENT".equals(s3.get().getStatus())) {
            throw new AssertionError("Student 3 should be marked ABSENT upon session closure");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 8: Simultaneous Scans Concurrency
    // ─────────────────────────────────────────────────────────────
    private static void testSimultaneousScans() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        courseDao.enrollStudent(1, 1);
        courseDao.enrollStudent(2, 1);

        QrSession session = createTestSession(1, 1);

        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);
        String tokenHash = PasswordUtil.hashPassword(activeToken);

        // Concurrently simulate 4 threads attempting to record attendance for Student 1 and Student 2
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateConflictCount = new AtomicInteger(0);

        List<Thread> workers = new ArrayList<>();
        // 2 threads for Student 1, 2 threads for Student 2
        for (int i = 0; i < threads; i++) {
            final int studentId = (i % 2 == 0) ? 1 : 2;
            workers.add(new Thread(() -> {
                try {
                    barrier.await();
                    boolean saved = attendanceDao.recordAttendanceTransaction(studentId, 1, session.getId(), "PRESENT", tokenHash);
                    if (saved) successCount.incrementAndGet();
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1062 || (e.getMessage() != null && e.getMessage().contains("Duplicate entry"))) {
                        duplicateConflictCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }));
        }

        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        // Exactly 2 successes (1 for Student 1, 1 for Student 2)
        if (successCount.get() != 2) {
            throw new AssertionError("Expected exactly 2 successful unique scan commits, got: " + successCount.get());
        }

        // Exactly 2 duplicate violations caught and rolled back
        if (duplicateConflictCount.get() != 2) {
            throw new AssertionError("Expected exactly 2 duplicate constraint violations caught, got: " + duplicateConflictCount.get());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 9: Transaction Rollback Integrity
    // ─────────────────────────────────────────────────────────────
    private static void testTransactionRollback() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();

        QrSession session = createTestSession(1, 1);

        // Manually simulate a failure within an uncommitted connection transaction
        try (Connection conn = DBConnection.getInstance().getConnection()) {
            conn.setAutoCommit(false);
            String sql = "INSERT INTO attendance (student_id, course_id, qr_session_id, status) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, 1);
                ps.setInt(2, 1);
                ps.setInt(3, session.getId());
                ps.setString(4, "PRESENT");
                ps.executeUpdate();
            }
            // Explicitly rollback
            conn.rollback();
            conn.setAutoCommit(true);
        }

        // Verify zero records persisted
        Optional<Attendance> record = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (record.isPresent()) {
            throw new AssertionError("Uncommitted transaction record was persisted! Rollback failed.");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 10: Typed Fallback Token Validation Identical to Camera
    // ─────────────────────────────────────────────────────────────
    private static void testTypedFallbackValidation() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        courseDao.enrollStudent(1, 1);

        QrSession session = createTestSession(1, 1);

        // Generate token
        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);

        // Student submits via typed fallback form (POST with token=activeToken)
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        String responseBody = sendPostScan(cmStudent, activeToken);
        if (!responseBody.contains("\"success\":true")) {
            throw new AssertionError("Typed fallback scan submission failed: " + responseBody);
        }

        Optional<Attendance> record = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (!record.isPresent() || !"PRESENT".equals(record.get().getStatus())) {
            throw new AssertionError("Attendance record not found for typed fallback scan");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 11: Multi-Student Active QR Scan & Per-Student Duplicate Rejection
    // ─────────────────────────────────────────────────────────────
    private static void testMultiStudentActiveQrAndDuplicateRejection() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        // 1. Enroll both Student 1 (Aarav, id 1) and Student 2 (Priya, id 2) in Course 1
        courseDao.enrollStudent(1, 1);
        courseDao.enrollStudent(2, 1);

        // 2. Create clean active test session
        QrSession session = createTestSession(1, 1);

        // 3. Generate active token and set as active on screen
        String activeToken = QrCodeUtil.generateSignedToken(session.getId(), 1);
        sessionDao.updateActiveToken(session.getId(), activeToken);

        // 4. Student 1 scans the active QR code -> Success (HTTP 200 / PRESENT)
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        String res1 = sendPostScan(cmStudent1, activeToken);
        if (!res1.contains("\"success\":true")) {
            throw new AssertionError("Student 1 scan of active QR expected success: true, got: " + res1);
        }

        // 5. Student 2 scans the SAME active QR code within valid window -> Success (HTTP 200 / PRESENT)
        CookieManager cmStudent2 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent2, "priya@student.com", "student123");
        String res2 = sendPostScan(cmStudent2, activeToken);
        if (!res2.contains("\"success\":true")) {
            throw new AssertionError("Student 2 scan of same active QR expected success: true, got: " + res2);
        }

        // 6. Repeat scan by Student 1 -> Rejected (DUPLICATE)
        String repeat1 = sendPostScan(cmStudent1, activeToken);
        if (!repeat1.contains("already marked")) {
            throw new AssertionError("Repeat scan by Student 1 should be rejected as duplicate, got: " + repeat1);
        }

        // 7. Repeat scan by Student 2 -> Rejected (DUPLICATE)
        String repeat2 = sendPostScan(cmStudent2, activeToken);
        if (!repeat2.contains("already marked")) {
            throw new AssertionError("Repeat scan by Student 2 should be rejected as duplicate, got: " + repeat2);
        }

        // 8. Verify both students have exactly 1 PRESENT record in DB
        Optional<Attendance> s1 = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (!s1.isPresent() || !"PRESENT".equals(s1.get().getStatus())) {
            throw new AssertionError("Student 1 record missing or not PRESENT in DB");
        }
        Optional<Attendance> s2 = attendanceDao.findBySessionAndStudent(session.getId(), 2);
        if (!s2.isPresent() || !"PRESENT".equals(s2.get().getStatus())) {
            throw new AssertionError("Student 2 record missing or not PRESENT in DB");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────
    private static void login(CookieManager cm, String email, String password) throws Exception {
        HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + "/login");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        String params = "email=" + email + "&password=" + password;
        conn.getOutputStream().write(params.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        saveCookies(cm, conn);
        if (code != 302 && code != 200) {
            throw new AssertionError("Login failed for " + email + ", code: " + code);
        }
    }

    private static String sendPostScan(CookieManager cm, String token) throws Exception {
        HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + "/qr/scan");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        String params = "token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
        conn.getOutputStream().write(params.getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        BufferedReader reader;
        if (code >= 200 && code < 400) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        saveCookies(cm, conn);
        return sb.toString();
    }

    private static void verifyScanAttemptOutcome(int sessionId, int studentId, String expectedOutcome) throws Exception {
        String sql = "SELECT outcome FROM scan_attempts WHERE session_id = ? AND student_id = ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.setInt(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String actual = rs.getString("outcome");
                    if (!expectedOutcome.equalsIgnoreCase(actual)) {
                        throw new AssertionError("Expected scan_attempt outcome: " + expectedOutcome + ", but found: " + actual);
                    }
                    return;
                }
            }
        }
        throw new AssertionError("No scan_attempt found for session " + sessionId + " and student " + studentId);
    }

    private static HttpURLConnection openConnectionWithCookies(CookieManager cm, String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        List<HttpCookie> cookies = cm.getCookieStore().get(URI.create(BASE_URL));
        if (!cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (HttpCookie cookie : cookies) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(cookie.getName()).append("=").append(cookie.getValue());
            }
            conn.setRequestProperty("Cookie", sb.toString());
        }
        return conn;
    }

    private static void saveCookies(CookieManager cm, HttpURLConnection conn) throws Exception {
        List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
        if (setCookies != null) {
            for (String header : setCookies) {
                List<HttpCookie> parsed = HttpCookie.parse(header);
                for (HttpCookie cookie : parsed) {
                    cm.getCookieStore().add(URI.create(BASE_URL), cookie);
                }
            }
        }
    }

    private static QrSession createTestSession(int courseId, int teacherId) {
        QrSessionDao sessionDao = new QrSessionDao();
        QrSession session = new QrSession();
        session.setCourseId(courseId);
        session.setTeacherId(teacherId);
        session.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        session.setActive(true);
        session.setSessionToken("test_init_" + java.util.UUID.randomUUID().toString());
        boolean saved = sessionDao.save(session);
        if (!saved || session.getId() == 0) {
            throw new RuntimeException("Failed to save unique test session");
        }
        return session;
    }
}
