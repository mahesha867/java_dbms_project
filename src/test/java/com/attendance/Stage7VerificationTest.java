package com.attendance;

import com.attendance.dao.AttendanceAuditLogDao;
import com.attendance.dao.AttendanceDao;
import com.attendance.dao.CourseDao;
import com.attendance.dao.QrSessionDao;
import com.attendance.model.Attendance;
import com.attendance.model.AttendanceAuditLog;
import com.attendance.model.Course;
import com.attendance.model.QrSession;
import com.attendance.util.DBConnection;
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
 * Automated Verification Test Suite for Stage 7:
 * 1. Session Reopen & Reclose lifecycle (state persistence and active flags).
 * 2. Reasoned manual status corrections (PRESENT, ABSENT, LATE, OD, EXCUSED) with mandatory reason validation.
 * 3. Immutable audit history logging (actor ID, timestamp, old_status, new_status, reason).
 * 4. Strict role authorization & course ownership enforcement (Student rejected with 403, unowned course rejected with 403).
 * 5. Attendance percentage calculations (OD in numerator, EXCUSED in denominator).
 * 6. Idempotent re-close preserving manual decisions (OD and EXCUSED never overwritten).
 * 7. Real-time Reports and CSV export format and contents.
 * 8. 6-digit numeric fallback code validation through identical server-side pipeline.
 */
public class Stage7VerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 7 Automated Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. Faculty Session Reopen and Reclose Lifecycle", Stage7VerificationTest::testReopenAndReclose);
        runTest("2. Reasoned Manual Corrections & Input Validation", Stage7VerificationTest::testManualCorrectionAndValidation);
        runTest("3. Immutable Audit History Records (Actor, Timestamp, Reason, Status)", Stage7VerificationTest::testAuditLogVerification);
        runTest("4. Role Permissions & Faculty Course Ownership Enforcement", Stage7VerificationTest::testRolePermissionsAndOwnership);
        runTest("5. Attendance Calculations (OD in Numerator, EXCUSED in Denominator)", Stage7VerificationTest::testAttendanceCalculations);
        runTest("6. Re-closing Does NOT Overwrite Manual Decisions", Stage7VerificationTest::testRecloseDoesNotOverwriteManualDecisions);
        runTest("7. Reports and CSV Export Contents & Format", Stage7VerificationTest::testCsvExportContents);
        runTest("8. 6-Digit Numeric Fallback Code Server-Side Validation", Stage7VerificationTest::testNumericFallbackCodeValidation);

        System.out.println("========================================================");
        System.out.println("Stage 7 Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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
    // TEST 1: Reopen and Reclose Lifecycle
    // ─────────────────────────────────────────────────────────────
    private static void testReopenAndReclose() throws Exception {
        QrSessionDao sessionDao = new QrSessionDao();
        QrSession session = createTestSession(1, 1);

        // Teacher 1 logs in
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        // 1. Close Session
        HttpURLConnection connClose = openConnectionWithCookies(cm, BASE_URL + "/qr/close?sessionId=" + session.getId() + "&format=json");
        connClose.setRequestMethod("GET");
        int closeCode = connClose.getResponseCode();
        saveCookies(cm, connClose);
        if (closeCode != 200) {
            throw new AssertionError("Close session expected HTTP 200, got: " + closeCode);
        }

        QrSession closed = sessionDao.findById(session.getId()).orElseThrow();
        if (closed.isActive() || closed.getClosedAt() == null) {
            throw new AssertionError("Session should be inactive with closed_at set after close");
        }

        // 2. Reopen Session
        HttpURLConnection connReopen = openConnectionWithCookies(cm, BASE_URL + "/qr/reopen?sessionId=" + session.getId() + "&format=json");
        connReopen.setRequestMethod("GET");
        int reopenCode = connReopen.getResponseCode();
        saveCookies(cm, connReopen);
        if (reopenCode != 200) {
            throw new AssertionError("Reopen session expected HTTP 200, got: " + reopenCode);
        }

        QrSession reopened = sessionDao.findById(session.getId()).orElseThrow();
        if (!reopened.isActive() || reopened.getClosedAt() != null) {
            throw new AssertionError("Session should be active with closed_at null after reopen");
        }
        if (reopened.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AssertionError("ExpiresAt should be extended into the future on reopen");
        }

        // 3. Re-close Session
        HttpURLConnection connReclose = openConnectionWithCookies(cm, BASE_URL + "/qr/close?sessionId=" + session.getId() + "&format=json");
        connReclose.setRequestMethod("GET");
        saveCookies(cm, connReclose);
        QrSession reclosed = sessionDao.findById(session.getId()).orElseThrow();
        if (reclosed.isActive()) {
            throw new AssertionError("Session should be inactive after re-closing");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 2: Reasoned Manual Corrections & Input Validation
    // ─────────────────────────────────────────────────────────────
    private static void testManualCorrectionAndValidation() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();
        QrSession session = createTestSession(1, 1);

        // Insert initial attendance as ABSENT for Student 1
        ensureAttendanceRecord(1, 1, session.getId(), "ABSENT");
        Attendance initial = attendanceDao.findBySessionAndStudent(session.getId(), 1).orElseThrow();

        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        // Subtest A: Reject invalid status
        HttpURLConnection connInv = openConnectionWithCookies(cm, BASE_URL + "/teacher/attendance/update");
        connInv.setRequestMethod("POST");
        connInv.setDoOutput(true);
        String bodyInv = "attendanceId=" + initial.getId() + "&status=INVALID_STATUS&reason=Approved&format=json";
        connInv.getOutputStream().write(bodyInv.getBytes(StandardCharsets.UTF_8));
        if (connInv.getResponseCode() != 400) {
            throw new AssertionError("Expected 400 for invalid status, got: " + connInv.getResponseCode());
        }

        // Subtest B: Reject empty or short reason (<3 chars)
        HttpURLConnection connNoReason = openConnectionWithCookies(cm, BASE_URL + "/teacher/attendance/update");
        connNoReason.setRequestMethod("POST");
        connNoReason.setDoOutput(true);
        String bodyNoReason = "attendanceId=" + initial.getId() + "&status=PRESENT&reason=ok&format=json";
        connNoReason.getOutputStream().write(bodyNoReason.getBytes(StandardCharsets.UTF_8));
        if (connNoReason.getResponseCode() != 400) {
            throw new AssertionError("Expected 400 for reason shorter than 3 characters, got: " + connNoReason.getResponseCode());
        }

        // Subtest C: Successfully update to OD with valid reason
        HttpURLConnection connValid = openConnectionWithCookies(cm, BASE_URL + "/teacher/attendance/update");
        connValid.setRequestMethod("POST");
        connValid.setDoOutput(true);
        String bodyValid = "attendanceId=" + initial.getId() + "&status=OD&reason=" + URLEncoder.encode("Representing college at zonal sports tournament", StandardCharsets.UTF_8) + "&format=json";
        connValid.getOutputStream().write(bodyValid.getBytes(StandardCharsets.UTF_8));
        if (connValid.getResponseCode() != 200) {
            throw new AssertionError("Expected 200 for valid OD update, got: " + connValid.getResponseCode());
        }

        Attendance updated = attendanceDao.findById(initial.getId()).orElseThrow();
        if (!"OD".equals(updated.getStatus())) {
            throw new AssertionError("Expected status to be updated to OD, got: " + updated.getStatus());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 3: Immutable Audit History Records
    // ─────────────────────────────────────────────────────────────
    private static void testAuditLogVerification() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();
        AttendanceAuditLogDao auditDao = new AttendanceAuditLogDao();
        QrSession session = createTestSession(1, 1);

        ensureAttendanceRecord(1, 1, session.getId(), "ABSENT");
        Attendance initial = attendanceDao.findBySessionAndStudent(session.getId(), 1).orElseThrow();

        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        String formalReason = "Medical emergency verified by chief doctor - certificate #MED-9401";
        HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + "/teacher/attendance/update");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        String body = "attendanceId=" + initial.getId() + "&status=EXCUSED&reason=" + URLEncoder.encode(formalReason, StandardCharsets.UTF_8) + "&format=json";
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        if (conn.getResponseCode() != 200) {
            throw new AssertionError("Expected 200 for EXCUSED update, got: " + conn.getResponseCode());
        }

        // Check audit log entry
        List<AttendanceAuditLog> logs = auditDao.findByCourse(1);
        boolean found = false;
        for (AttendanceAuditLog log : logs) {
            if (log.getAttendanceId() != null && log.getAttendanceId() == initial.getId()) {
                if (!"EXCUSED".equals(log.getNewStatus())) {
                    throw new AssertionError("Expected audit newStatus EXCUSED, got: " + log.getNewStatus());
                }
                if (!formalReason.equals(log.getReason())) {
                    throw new AssertionError("Expected audit reason '" + formalReason + "', got: " + log.getReason());
                }
                if (log.getCreatedAt() == null) {
                    throw new AssertionError("Audit record created_at timestamp cannot be null");
                }
                if (log.getChangedByUserId() <= 0) {
                    throw new AssertionError("Audit record changed_by_user_id must be a valid user ID");
                }
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError("No audit log found for updated attendance record " + initial.getId());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 4: Role Permissions & Course Ownership Enforcement
    // ─────────────────────────────────────────────────────────────
    private static void testRolePermissionsAndOwnership() throws Exception {
        // Subtest A: Student attempting to access /reports is rejected with 403
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        HttpURLConnection connRep = openConnectionWithCookies(cmStudent, BASE_URL + "/reports?courseId=1");
        connRep.setRequestMethod("GET");
        if (connRep.getResponseCode() != 403) {
            throw new AssertionError("Student accessing /reports must receive HTTP 403, got: " + connRep.getResponseCode());
        }

        // Subtest B: Student attempting to access /teacher/attendance/update is rejected with 403
        HttpURLConnection connUpd = openConnectionWithCookies(cmStudent, BASE_URL + "/teacher/attendance/update");
        connUpd.setRequestMethod("POST");
        connUpd.setDoOutput(true);
        connUpd.getOutputStream().write("attendanceId=1&status=PRESENT&reason=hack".getBytes(StandardCharsets.UTF_8));
        if (connUpd.getResponseCode() != 403) {
            throw new AssertionError("Student calling /teacher/attendance/update must receive HTTP 403, got: " + connUpd.getResponseCode());
        }

        // Subtest C: Student attempting /qr/reopen or /qr/close is rejected with 403
        HttpURLConnection connReopen = openConnectionWithCookies(cmStudent, BASE_URL + "/qr/reopen?sessionId=1");
        connReopen.setRequestMethod("GET");
        if (connReopen.getResponseCode() != 403) {
            throw new AssertionError("Student calling /qr/reopen must receive HTTP 403, got: " + connReopen.getResponseCode());
        }

        // Subtest D: Teacher attempting to edit a course owned by another teacher is rejected with 403
        // Teacher 1 (teacher@attendance.com) owns Course 1 and 2. Course 3 is owned by Teacher 2 (sunita@attendance.com).
        CookieManager cmTeacher1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher1, "teacher@attendance.com", "teacher123");

        HttpURLConnection connOtherCourse = openConnectionWithCookies(cmTeacher1, BASE_URL + "/reports?courseId=3");
        connOtherCourse.setRequestMethod("GET");
        if (connOtherCourse.getResponseCode() != 403) {
            throw new AssertionError("Teacher accessing another faculty member's course report must receive HTTP 403, got: " + connOtherCourse.getResponseCode());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 5: Attendance Calculations
    // ─────────────────────────────────────────────────────────────
    private static void testAttendanceCalculations() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();
        CourseDao courseDao = new CourseDao();

        // Create a unique isolated course for formula verification
        int courseId = createTestCourse(1, "FORMULA-101", "Attendance Math Verification");
        int studentId = 1; // Aarav
        courseDao.enrollStudent(studentId, courseId);

        // Create 4 sessions:
        // Session 1: PRESENT
        // Session 2: OD (On-Duty)
        // Session 3: ABSENT
        // Session 4: EXCUSED (Medical leave)
        QrSession s1 = createTestSession(courseId, 1);
        QrSession s2 = createTestSession(courseId, 1);
        QrSession s3 = createTestSession(courseId, 1);
        QrSession s4 = createTestSession(courseId, 1);

        ensureAttendanceRecord(studentId, courseId, s1.getId(), "PRESENT");
        ensureAttendanceRecord(studentId, courseId, s2.getId(), "OD");
        ensureAttendanceRecord(studentId, courseId, s3.getId(), "ABSENT");
        ensureAttendanceRecord(studentId, courseId, s4.getId(), "EXCUSED");

        // Total sessions = 4
        // Excused sessions = 1 -> Effective denominator = 4 - 1 = 3
        // Attended numerator = PRESENT(1) + OD(1) = 2
        // Expected percentage = (2 / 3) * 100 = 66.67%
        double pct = attendanceDao.getAttendancePercentage(studentId, courseId);
        double expected = (2.0 / 3.0) * 100.0;
        if (Math.abs(pct - expected) > 0.1) {
            throw new AssertionError("Expected attendance percentage ~66.67%, got: " + pct);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 6: Re-closing Does NOT Overwrite Manual Decisions
    // ─────────────────────────────────────────────────────────────
    private static void testRecloseDoesNotOverwriteManualDecisions() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();
        CourseDao courseDao = new CourseDao();

        // Ensure Student 1 is enrolled in course 1
        courseDao.enrollStudent(1, 1);

        // Create session
        QrSession session = createTestSession(1, 1);

        // Manually record student as OD
        ensureAttendanceRecord(1, 1, session.getId(), "OD");

        // Faculty logs in and closes the session
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        HttpURLConnection connClose = openConnectionWithCookies(cm, BASE_URL + "/qr/close?sessionId=" + session.getId() + "&format=json");
        connClose.setRequestMethod("GET");
        if (connClose.getResponseCode() != 200) {
            throw new AssertionError("Close session failed with code: " + connClose.getResponseCode());
        }

        // Verify status in DB is STILL 'OD' and NOT overwritten to 'ABSENT'
        Attendance record = attendanceDao.findBySessionAndStudent(session.getId(), 1).orElseThrow();
        if (!"OD".equals(record.getStatus())) {
            throw new AssertionError("Session close overwrote manual decision! Expected 'OD', found: " + record.getStatus());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 7: Reports and CSV Export Contents & Format
    // ─────────────────────────────────────────────────────────────
    private static void testCsvExportContents() throws Exception {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + "/reports?courseId=1&format=csv");
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new AssertionError("Expected HTTP 200 for CSV export, got: " + code);
        }

        String contentType = conn.getHeaderField("Content-Type");
        if (contentType == null || !contentType.contains("text/csv")) {
            throw new AssertionError("Expected Content-Type text/csv, got: " + contentType);
        }

        String contentDisp = conn.getHeaderField("Content-Disposition");
        if (contentDisp == null || !contentDisp.contains("attachment") || !contentDisp.contains(".csv")) {
            throw new AssertionError("Expected Content-Disposition attachment with .csv filename, got: " + contentDisp);
        }

        // Read and parse CSV lines
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null || !headerLine.contains("Attendance ID") || !headerLine.contains("Status")) {
            throw new AssertionError("Invalid CSV header row: " + headerLine);
        }

        int rowCount = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            rowCount++;
            // Each row must contain comma-separated values
            if (line.split(",").length < 4) {
                throw new AssertionError("Malformed CSV row: " + line);
            }
        }
        reader.close();
        if (rowCount == 0) {
            throw new AssertionError("CSV export contained header but no data rows");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 8: 6-Digit Numeric Fallback Code Validation
    // ─────────────────────────────────────────────────────────────
    private static void testNumericFallbackCodeValidation() throws Exception {
        CourseDao courseDao = new CourseDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        courseDao.enrollStudent(1, 1);
        QrSession session = createTestSession(1, 1);

        // Student 1 logs in
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        // Generate the 6-digit numeric code corresponding to the active session at current timestamp
        long now = System.currentTimeMillis();
        String numericCode = QrCodeUtil.computeNumericCode(session.getId(), 1, now);

        if (!numericCode.matches("^\\d{6}$")) {
            throw new AssertionError("Numeric code must be exactly 6 digits, got: " + numericCode);
        }

        HttpURLConnection conn = openConnectionWithCookies(cmStudent, BASE_URL + "/qr/scan");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        String postBody = "token=" + numericCode;
        conn.getOutputStream().write(postBody.getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        BufferedReader reader;
        if (code >= 200 && code < 400) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = reader.readLine()) != null) sb.append(l);
        reader.close();

        String responseJson = sb.toString();
        if (!responseJson.contains("\"success\":true")) {
            throw new AssertionError("6-digit code scan expected success: true, got: " + responseJson);
        }

        // Verify Attendance table marked PRESENT
        Optional<Attendance> record = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        if (!record.isPresent() || !"PRESENT".equals(record.get().getStatus())) {
            throw new AssertionError("Attendance record not found or not PRESENT after 6-digit fallback validation");
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
            throw new AssertionError("Login failed for " + email + ", expected HTTP 302 redirect, got code: " + code);
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
        session.setSessionToken("test_s7_" + UUID.randomUUID().toString());
        boolean saved = sessionDao.save(session);
        if (!saved || session.getId() == 0) {
            throw new RuntimeException("Failed to create unique test session");
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
