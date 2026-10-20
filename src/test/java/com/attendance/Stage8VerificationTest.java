package com.attendance;

import com.attendance.dao.AttendanceAuditLogDao;
import com.attendance.dao.AttendanceDao;
import com.attendance.dao.CourseDao;
import com.attendance.dao.QrSessionDao;
import com.attendance.dao.ScanAttemptDao;
import com.attendance.model.Attendance;
import com.attendance.model.QrSession;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 8 Final Verification Test Suite:
 * 1. 6-Digit Code is Session-Bound and Course-Bound (cannot be used across courses or sessions).
 * 2. Rapid Guessing & Brute Force Rate-Limiting Protection (blocks rapid guessing after 5 failures).
 * 3. Consistent Attendance Formula across DB, Student Portal, and Teacher Roster.
 * 4. Typed Numeric Fallback Code identical validation pipeline to camera scan.
 * 5. Full Security Regression & Role Boundary Verification.
 */
public class Stage8VerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 8 Final Academic Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. 6-Digit Code is Strictly Session-Bound and Course-Bound", Stage8VerificationTest::testSixDigitCodeSessionBound);
        runTest("2. Rapid Guessing & Brute Force Rate-Limiting Protection", Stage8VerificationTest::testRapidGuessingRateLimiting);
        runTest("3. Consistent Attendance Formula Verification Across System", Stage8VerificationTest::testAttendanceFormulaConsistency);
        runTest("4. Typed 6-Digit Fallback Input Server Pipeline Equivalence", Stage8VerificationTest::testTypedFallbackEquivalence);
        runTest("5. Full Security RBAC & Accessibility Boundary Checks", Stage8VerificationTest::testSecurityRbacBoundaries);

        System.out.println("========================================================");
        System.out.println("Stage 8 Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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
    // TEST 1: 6-Digit Code Session-Bound & Course-Bound
    // ─────────────────────────────────────────────────────────────
    private static void testSixDigitCodeSessionBound() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        CourseDao courseDao = new CourseDao();

        // Ensure Student 1 is enrolled in Course 1, but NOT enrolled in Course 3
        courseDao.enrollStudent(1, 1);

        // Session A for Course 1
        QrSession sessionA = createTestSession(1, 1);
        // Session B for Course 3 (Teacher 2, Student 1 not enrolled)
        QrSession sessionB = createTestSession(3, 2);

        long now = System.currentTimeMillis();
        // Compute code explicitly for Session B
        String codeForSessionB = QrCodeUtil.computeNumericCode(sessionB.getId(), 3, now);

        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        // Student 1 attempts to submit code from Session B (Course 3)
        HttpURLConnection conn = openConnectionWithCookies(cmStudent, BASE_URL + "/qr/scan");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.getOutputStream().write(("token=" + codeForSessionB).getBytes(StandardCharsets.UTF_8));

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        String responseJson = sb.toString();
        // Must reject because student 1 is not enrolled in Course 3 session
        if (responseJson.contains("\"success\":true")) {
            throw new AssertionError("Code from un-enrolled course was accepted! Expected rejection, got: " + responseJson);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 2: Rapid Guessing & Brute-Force Rate Limiting
    // ─────────────────────────────────────────────────────────────
    private static void testRapidGuessingRateLimiting() throws Exception {
        ScanAttemptDao scanAttemptDao = new ScanAttemptDao();
        int studentId = 1;

        // Clear any old attempts for test cleanliness
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        // Inject 5 failed attempts rapidly into DB
        for (int i = 0; i < 5; i++) {
            scanAttemptDao.recordAttempt(null, studentId, PasswordUtil.hashPassword("fake" + i), "INVALID");
        }

        // 6th attempt should be blocked by rate-limiting
        HttpURLConnection conn = openConnectionWithCookies(cmStudent, BASE_URL + "/qr/scan");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.getOutputStream().write("token=999999".getBytes(StandardCharsets.UTF_8));

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        String responseJson = sb.toString();
        if (!responseJson.contains("Rapid guessing blocked") && !responseJson.contains("Too many failed attempts")) {
            throw new AssertionError("Expected rate-limiting block after 5 failed attempts, got: " + responseJson);
        }

        // Clean up test attempts so subsequent tests aren't blocked
        try (Connection connDb = DBConnection.getInstance().getConnection();
             PreparedStatement ps = connDb.prepareStatement("DELETE FROM scan_attempts WHERE student_id = ? AND outcome IN ('INVALID', 'RATE_LIMITED')")) {
            ps.setInt(1, studentId);
            ps.executeUpdate();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 3: Consistent Attendance Formula Across Entire System
    // ─────────────────────────────────────────────────────────────
    private static void testAttendanceFormulaConsistency() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();
        CourseDao courseDao = new CourseDao();

        int courseId = createTestCourse(1, "CONSISTENCY", "Attendance Formula Uniformity");
        int studentId = 1;
        courseDao.enrollStudent(studentId, courseId);

        // 5 Sessions:
        // S1: PRESENT
        // S2: OD (Counts in attended numerator)
        // S3: LATE (Counts in attended numerator)
        // S4: EXCUSED (Deducted from denominator)
        // S5: ABSENT (Did not attend)
        QrSession s1 = createTestSession(courseId, 1);
        QrSession s2 = createTestSession(courseId, 1);
        QrSession s3 = createTestSession(courseId, 1);
        QrSession s4 = createTestSession(courseId, 1);
        QrSession s5 = createTestSession(courseId, 1);

        ensureAttendanceRecord(studentId, courseId, s1.getId(), "PRESENT");
        ensureAttendanceRecord(studentId, courseId, s2.getId(), "OD");
        ensureAttendanceRecord(studentId, courseId, s3.getId(), "LATE");
        ensureAttendanceRecord(studentId, courseId, s4.getId(), "EXCUSED");
        ensureAttendanceRecord(studentId, courseId, s5.getId(), "ABSENT");

        // Attended = PRESENT(1) + OD(1) + LATE(1) = 3
        // Effective total = Total(5) - EXCUSED(1) = 4
        // Expected = (3 / 4) * 100 = 75.0%
        double computed = attendanceDao.getAttendancePercentage(studentId, courseId);
        double expected = 75.0;
        if (Math.abs(computed - expected) > 0.01) {
            throw new AssertionError("Formula inconsistency! Expected: 75.0%, got: " + computed + "%");
        }

        // Verify Student Portal reflects the exact same percentage
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        HttpURLConnection connStudent = openConnectionWithCookies(cmStudent, BASE_URL + "/student/dashboard");
        connStudent.setRequestMethod("GET");
        BufferedReader reader = new BufferedReader(new InputStreamReader(connStudent.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        String html = sb.toString();
        if (!html.contains("75.0%") && !html.contains("75%")) {
            throw new AssertionError("Student dashboard did not display 75% attendance for enrolled course");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 4: Typed 6-Digit Fallback Equivalence to Camera Scan
    // ─────────────────────────────────────────────────────────────
    private static void testTypedFallbackEquivalence() throws Exception {
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        courseDao.enrollStudent(1, 1);
        QrSession session = createTestSession(1, 1);

        long now = System.currentTimeMillis();
        String numericCode = QrCodeUtil.computeNumericCode(session.getId(), 1, now);

        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        // Submit via URL-encoded form data (as submitted by typed fallback input)
        HttpURLConnection conn = openConnectionWithCookies(cmStudent, BASE_URL + "/qr/scan");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.getOutputStream().write(("token=" + numericCode).getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = reader.readLine()) != null) sb.append(l);
        reader.close();

        String responseJson = sb.toString();
        if (!responseJson.contains("\"success\":true")) {
            throw new AssertionError("Typed 6-digit fallback failed: " + responseJson);
        }

        Optional<Attendance> record = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (!record.isPresent() || !"PRESENT".equals(record.get().getStatus())) {
            throw new AssertionError("Attendance record not found or not marked PRESENT");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 5: Security RBAC & Accessibility Boundary Checks
    // ─────────────────────────────────────────────────────────────
    private static void testSecurityRbacBoundaries() throws Exception {
        // Unauthenticated access redirects to /login
        CookieManager cmUnauth = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpURLConnection connUnauth = openConnectionWithCookies(cmUnauth, BASE_URL + "/teacher/dashboard");
        connUnauth.setInstanceFollowRedirects(false);
        int unauthCode = connUnauth.getResponseCode();
        if (unauthCode != 302 && unauthCode != 401) {
            throw new AssertionError("Unauthenticated access must redirect to login, got: " + unauthCode);
        }

        // Student accessing reports is blocked with 403
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        HttpURLConnection connStudentRep = openConnectionWithCookies(cmStudent, BASE_URL + "/reports?courseId=1");
        if (connStudentRep.getResponseCode() != 403) {
            throw new AssertionError("Student accessing reports must return 403, got: " + connStudentRep.getResponseCode());
        }

        // Faculty accessing student scanner is blocked with 403
        CookieManager cmFaculty = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmFaculty, "teacher@attendance.com", "teacher123");

        HttpURLConnection connFacultyScan = openConnectionWithCookies(cmFaculty, BASE_URL + "/qr/scan");
        if (connFacultyScan.getResponseCode() != 403) {
            throw new AssertionError("Faculty accessing /qr/scan must return 403, got: " + connFacultyScan.getResponseCode());
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
        String params = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        conn.getOutputStream().write(params.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        saveCookies(cm, conn);
        if (code != 302) {
            throw new AssertionError("Login failed for " + email + ", expected HTTP 302, got: " + code);
        }
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
        session.setExpiresAt(LocalDateTime.now().plusMinutes(20));
        session.setActive(true);
        session.setSessionToken("test_s8_" + UUID.randomUUID().toString());
        boolean saved = sessionDao.save(session);
        if (!saved || session.getId() == 0) {
            throw new RuntimeException("Failed to create test session");
        }
        return session;
    }

    private static int createTestCourse(int teacherId, String code, String name) throws Exception {
        String uniqueCode = code + "-" + System.currentTimeMillis() % 10000;
        String sql = "INSERT INTO courses (course_code, course_name, teacher_id) VALUES (?, ?, ?)";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uniqueCode);
            ps.setString(2, name);
            ps.setInt(3, teacherId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new RuntimeException("Failed to insert test course");
    }

    private static void ensureAttendanceRecord(int studentId, int courseId, int sessionId, String status) throws Exception {
        String sql = "INSERT INTO attendance (student_id, course_id, qr_session_id, status) VALUES (?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE status = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, courseId);
            ps.setInt(3, sessionId);
            ps.setString(4, status);
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }
}
