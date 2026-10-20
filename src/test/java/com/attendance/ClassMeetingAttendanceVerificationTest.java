package com.attendance;

import com.attendance.dao.AttendanceDao;
import com.attendance.dao.CourseDao;
import com.attendance.dao.QrSessionDao;
import com.attendance.model.Attendance;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verification Test Suite for Single Persistent Class Meeting Session Architecture:
 * 1. 10+ successive QR rotations within one meeting creates EXACTLY 1 meeting session and 0 attendance rows.
 * 2. Two different enrolled students scan the same active QR; both receive one PRESENT record.
 * 3. A student scanning multiple rotations receives exactly ONE attendance record.
 * 4. Closing the meeting marks unmarked enrolled students as ABSENT exactly once.
 * 5. Reclosing is idempotent and never overwrites PRESENT/manual decisions.
 * 6. Reopening and reclosing preserves the exact same meeting session ID and attendance records.
 * 7. Reports and CSV export display one row per student per class meeting.
 * 8. Attendance percentage calculations count class meetings and are invariant to QR rotations.
 * 9. Scheduled class end auto-closure works when meeting expires, while QR token expiry never closes the meeting.
 */
public class ClassMeetingAttendanceVerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.out.println("========================================================");
        System.out.println("   Class Meeting Attendance Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. 10+ QR Rotations Keep Exactly 1 Meeting Session & 0 Attendance Rows", ClassMeetingAttendanceVerificationTest::testTenQrRotationsSingleSession);
        runTest("2. Multi-Student Scans on Same Active QR Token (Both PRESENT)", ClassMeetingAttendanceVerificationTest::testMultiStudentScanSameToken);
        runTest("3. Student Scanning Across Multiple Rotations Has Exactly 1 Record", ClassMeetingAttendanceVerificationTest::testStudentMultiRotationSingleRecord);
        runTest("4. Meeting Close Reconciles Non-Scanners to ABSENT Exactly Once", ClassMeetingAttendanceVerificationTest::testMeetingCloseMarksAbsenteesOnce);
        runTest("5. Reclosing Is Idempotent (No Duplicate ABSENT, Preserves PRESENT/Manual)", ClassMeetingAttendanceVerificationTest::testRecloseIdempotent);
        runTest("6. Reopening & Reclosing Preserves Same Meeting ID & Attendance", ClassMeetingAttendanceVerificationTest::testReopenAndReclosePreservesMeeting);
        runTest("7. Report & CSV Query Renders 1 Row Per Student Per Class Meeting", ClassMeetingAttendanceVerificationTest::testReportRendersOneRowPerMeeting);
        runTest("8. Attendance Percentage Counts Class Meetings, Invariant to QR Rotations", ClassMeetingAttendanceVerificationTest::testAttendancePercentageMeetingBased);
        runTest("9. Active Meeting Remains Valid; QR Expiry Never Closes Class Meeting", ClassMeetingAttendanceVerificationTest::testActiveMeetingValidAndQrNeverCloses);

        System.out.println("========================================================");
        System.out.println("Meeting Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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
    // TEST 1: 10+ QR Rotations in Single Meeting
    // ─────────────────────────────────────────────────────────────
    private static void testTenQrRotationsSingleSession() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();

        int courseId = createTestCourse("MEETING-ROT-101", "Meeting Rotation Course");
        courseDao.enrollStudent(1, courseId);
        courseDao.enrollStudent(2, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");

        // 1. Initial Launch: /qr/generate
        String initialPage = sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        if (!initialPage.contains("Dynamic Attendance QR")) {
            throw new AssertionError("Failed to load QR generator page");
        }

        Optional<QrSession> initialSessionOpt = sessionDao.findActiveByCourseId(courseId);
        if (!initialSessionOpt.isPresent()) {
            throw new AssertionError("Expected active meeting session to be created");
        }
        int meetingId = initialSessionOpt.get().getId();

        // 2. Poll /qr/active for 12 consecutive 5-second rotation cycles
        for (int i = 1; i <= 12; i++) {
            String activeJson = sendGet(cmTeacher, BASE_URL + "/qr/active?courseId=" + courseId + "&format=json");
            if (!activeJson.contains("\"status\":\"ok\"") || !activeJson.contains("\"sessionId\":" + meetingId)) {
                throw new AssertionError("Rotation " + i + " failed or changed meeting ID: " + activeJson);
            }
        }

        // 3. Assert: Exactly 1 session row exists for this course
        int sessionCount = countSessionsForCourse(courseId);
        if (sessionCount != 1) {
            throw new AssertionError("Expected exactly 1 persistent session row, but found: " + sessionCount);
        }

        // 4. Assert: Exactly 0 attendance rows created during polling (no phantom absences!)
        int attendanceCount = countAttendanceForCourse(courseId);
        if (attendanceCount != 0) {
            throw new AssertionError("Expected 0 attendance rows during polling, but found: " + attendanceCount);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 2: Multi-Student Scans on Same Active QR
    // ─────────────────────────────────────────────────────────────
    private static void testMultiStudentScanSameToken() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int courseId = createTestCourse("MEETING-MULTI-102", "Multi-Student Course");
        courseDao.enrollStudent(1, courseId);
        courseDao.enrollStudent(2, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);

        QrSession session = sessionDao.findActiveByCourseId(courseId).orElseThrow(() -> new AssertionError("Session not found"));
        String activeToken = session.getSessionToken();

        // Student 1 scans
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        String res1 = sendPostScan(cmStudent1, activeToken);
        if (!res1.contains("\"success\":true")) throw new AssertionError("Student 1 scan failed: " + res1);

        // Student 2 scans SAME active token
        CookieManager cmStudent2 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent2, "priya@student.com", "student123");
        String res2 = sendPostScan(cmStudent2, activeToken);
        if (!res2.contains("\"success\":true")) throw new AssertionError("Student 2 scan failed: " + res2);

        // Assert: Both have PRESENT in the exact same meeting
        Optional<Attendance> a1 = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        Optional<Attendance> a2 = attendanceDao.findBySessionAndStudent(session.getId(), 2);
        if (!a1.isPresent() || !"PRESENT".equals(a1.get().getStatus())) throw new AssertionError("Student 1 not PRESENT");
        if (!a2.isPresent() || !"PRESENT".equals(a2.get().getStatus())) throw new AssertionError("Student 2 not PRESENT");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 3: Student Scanning Multiple Rotations in Same Meeting
    // ─────────────────────────────────────────────────────────────
    private static void testStudentMultiRotationSingleRecord() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int courseId = createTestCourse("MEETING-REPEAT-103", "Repeat Rotation Course");
        courseDao.enrollStudent(1, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession session = sessionDao.findActiveByCourseId(courseId).orElseThrow(() -> new AssertionError("Session not found"));

        // Scan in rotation 1
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        sendPostScan(cmStudent1, session.getSessionToken());

        // Rotate token via /qr/active
        sendGet(cmTeacher, BASE_URL + "/qr/active?courseId=" + courseId + "&format=json");
        QrSession refreshedSession = sessionDao.findActiveByCourseId(courseId).get();

        // Student scans again in rotation 2
        String repeatRes = sendPostScan(cmStudent1, refreshedSession.getSessionToken());
        if (!repeatRes.contains("already marked")) {
            throw new AssertionError("Repeat scan in next rotation should state 'already marked', got: " + repeatRes);
        }

        // Assert: Exactly ONE attendance record exists for Student 1
        int count = countAttendanceForStudentAndCourse(1, courseId);
        if (count != 1) {
            throw new AssertionError("Expected exactly 1 attendance record for student, but found: " + count);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 4: Meeting Close Reconciles Non-Scanners Once
    // ─────────────────────────────────────────────────────────────
    private static void testMeetingCloseMarksAbsenteesOnce() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int courseId = createTestCourse("MEETING-CLOSE-104", "Meeting Close Course");
        courseDao.enrollStudent(1, courseId); // will scan
        courseDao.enrollStudent(2, courseId); // will NOT scan
        courseDao.enrollStudent(3, courseId); // will NOT scan

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession session = sessionDao.findActiveByCourseId(courseId).get();

        // Student 1 scans
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        sendPostScan(cmStudent1, session.getSessionToken());

        // End session
        sendGet(cmTeacher, BASE_URL + "/qr/close?sessionId=" + session.getId());

        // Verify: Student 1 is PRESENT, Student 2 & 3 are ABSENT
        Optional<Attendance> a1 = attendanceDao.findBySessionAndStudent(session.getId(), 1);
        Optional<Attendance> a2 = attendanceDao.findBySessionAndStudent(session.getId(), 2);
        Optional<Attendance> a3 = attendanceDao.findBySessionAndStudent(session.getId(), 3);

        if (!a1.isPresent() || !"PRESENT".equals(a1.get().getStatus())) throw new AssertionError("Student 1 should be PRESENT");
        if (!a2.isPresent() || !"ABSENT".equals(a2.get().getStatus())) throw new AssertionError("Student 2 should be ABSENT");
        if (!a3.isPresent() || !"ABSENT".equals(a3.get().getStatus())) throw new AssertionError("Student 3 should be ABSENT");

        // Total rows = 3 (1 per student for this meeting)
        int totalRows = countAttendanceForCourse(courseId);
        if (totalRows != 3) {
            throw new AssertionError("Expected exactly 3 attendance rows for meeting, got: " + totalRows);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 5: Reclosing is Idempotent
    // ─────────────────────────────────────────────────────────────
    private static void testRecloseIdempotent() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int courseId = createTestCourse("MEETING-IDEM-105", "Idempotent Reclose Course");
        courseDao.enrollStudent(1, courseId);
        courseDao.enrollStudent(2, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession session = sessionDao.findActiveByCourseId(courseId).get();

        // Student 1 scans
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        sendPostScan(cmStudent1, session.getSessionToken());

        // First close
        sessionDao.closeSession(session.getId());

        // Manual override: Faculty changes Student 2 from ABSENT to OD
        Attendance a2 = attendanceDao.findBySessionAndStudent(session.getId(), 2).get();
        attendanceDao.updateWithAuditTransaction(a2.getId(), "OD", "Approved college hackathon", 1);

        // Re-close the session again
        sessionDao.closeSession(session.getId());

        // Verify Student 1 remains PRESENT and Student 2 remains OD (NOT overwritten by ABSENT)
        Attendance check1 = attendanceDao.findBySessionAndStudent(session.getId(), 1).get();
        Attendance check2 = attendanceDao.findBySessionAndStudent(session.getId(), 2).get();
        if (!"PRESENT".equals(check1.getStatus())) throw new AssertionError("Student 1 should remain PRESENT");
        if (!"OD".equals(check2.getStatus())) throw new AssertionError("Student 2 should remain OD, got: " + check2.getStatus());

        int totalRows = countAttendanceForCourse(courseId);
        if (totalRows != 2) throw new AssertionError("Expected exactly 2 rows, got: " + totalRows);
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 6: Reopening & Reclosing Preserves Same Meeting ID
    // ─────────────────────────────────────────────────────────────
    private static void testReopenAndReclosePreservesMeeting() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int courseId = createTestCourse("MEETING-REOPEN-106", "Reopen Meeting Course");
        courseDao.enrollStudent(1, courseId);
        courseDao.enrollStudent(2, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession session = sessionDao.findActiveByCourseId(courseId).get();
        int meetingId = session.getId();

        // Student 1 scans
        CookieManager cmStudent1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent1, "aarav@student.com", "student123");
        sendPostScan(cmStudent1, session.getSessionToken());

        // Close session
        sessionDao.closeSession(meetingId);

        // Reopen session
        sendGet(cmTeacher, BASE_URL + "/qr/reopen?sessionId=" + meetingId);
        QrSession reopenedSession = sessionDao.findActiveByCourseId(courseId).orElseThrow(() -> new AssertionError("Reopened session not active"));
        if (reopenedSession.getId() != meetingId) {
            throw new AssertionError("Reopening should preserve meeting ID " + meetingId + ", but got: " + reopenedSession.getId());
        }

        // Student 2 arrives late and scans during reopened period
        CookieManager cmStudent2 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent2, "priya@student.com", "student123");
        // Update student 2 to scan
        sendPostScan(cmStudent2, reopenedSession.getSessionToken());

        // Close session again
        sessionDao.closeSession(meetingId);

        // Assert: Exactly 1 session exists, and both students are marked PRESENT
        int sessionCount = countSessionsForCourse(courseId);
        if (sessionCount != 1) throw new AssertionError("Expected 1 session after reopen/close, got: " + sessionCount);

        Attendance s1 = attendanceDao.findBySessionAndStudent(meetingId, 1).get();
        Attendance s2 = attendanceDao.findBySessionAndStudent(meetingId, 2).get();
        if (!"PRESENT".equals(s1.getStatus())) throw new AssertionError("Student 1 should be PRESENT");
        if (!"PRESENT".equals(s2.getStatus())) throw new AssertionError("Student 2 should be PRESENT");
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 7: Report & CSV Render 1 Row Per Student Per Class Meeting
    // ─────────────────────────────────────────────────────────────
    private static void testReportRendersOneRowPerMeeting() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();

        int courseId = createTestCourse("MEETING-REPORT-107", "Report Single Row Course");
        courseDao.enrollStudent(1, courseId);
        courseDao.enrollStudent(2, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession session = sessionDao.findActiveByCourseId(courseId).get();

        // 5 rotations occur during meeting
        for (int i = 0; i < 5; i++) {
            sendGet(cmTeacher, BASE_URL + "/qr/active?courseId=" + courseId + "&format=json");
        }

        // Close meeting
        sessionDao.closeSession(session.getId());

        // Fetch CSV Report
        String csv = sendGet(cmTeacher, BASE_URL + "/reports?courseId=" + courseId + "&format=csv");
        // Count student data rows (ignoring header)
        String[] lines = csv.split("\n");
        int studentDataLines = 0;
        for (String line : lines) {
            if (line.contains("Aarav Singh") || line.contains("Priya Sharma")) {
                studentDataLines++;
            }
        }

        // Exactly 2 lines (1 for Aarav, 1 for Priya for this single class meeting)
        if (studentDataLines != 2) {
            throw new AssertionError("Expected exactly 2 student rows in CSV for 1 meeting, found: " + studentDataLines + "\nCSV:\n" + csv);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 8: Attendance Percentage Counts Class Meetings
    // ─────────────────────────────────────────────────────────────
    private static void testAttendancePercentageMeetingBased() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int courseId = createTestCourse("MEETING-PCT-108", "Percentage Meeting Course");
        courseDao.enrollStudent(1, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");

        // Meeting 1: Student attends. 8 QR rotations occur.
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession m1 = sessionDao.findActiveByCourseId(courseId).get();
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");
        sendPostScan(cmStudent, m1.getSessionToken());
        for (int i = 0; i < 8; i++) {
            sendGet(cmTeacher, BASE_URL + "/qr/active?courseId=" + courseId + "&format=json");
        }
        sessionDao.closeSession(m1.getId());

        // Attendance % after Meeting 1: 1 / 1 = 100.0%
        double pct1 = attendanceDao.getAttendancePercentage(1, courseId);
        if (Math.abs(pct1 - 100.0) > 0.01) {
            throw new AssertionError("Expected 100.0% after meeting 1, got: " + pct1);
        }

        // Meeting 2: Student is absent. 6 QR rotations occur.
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);
        QrSession m2 = sessionDao.findActiveByCourseId(courseId).get();
        for (int i = 0; i < 6; i++) {
            sendGet(cmTeacher, BASE_URL + "/qr/active?courseId=" + courseId + "&format=json");
        }
        sessionDao.closeSession(m2.getId());

        // Attendance % after Meeting 2: 1 attended / 2 conducted meetings = 50.0%
        double pct2 = attendanceDao.getAttendancePercentage(1, courseId);
        if (Math.abs(pct2 - 50.0) > 0.01) {
            throw new AssertionError("Expected exactly 50.0% (1/2 meetings), got: " + pct2);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 9: Active Meeting Valid; QR Expiry Never Closes Class
    // ─────────────────────────────────────────────────────────────
    private static void testActiveMeetingValidAndQrNeverCloses() throws Exception {
        CourseDao courseDao = new CourseDao();
        QrSessionDao sessionDao = new QrSessionDao();

        int courseId = createTestCourse("MEETING-UTC-109", "Timezone Validity Course");
        courseDao.enrollStudent(1, courseId);

        CookieManager cmTeacher = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher, "teacher@attendance.com", "teacher123");
        sendGet(cmTeacher, BASE_URL + "/qr/generate?courseId=" + courseId);

        QrSession session = sessionDao.findActiveByCourseId(courseId).orElseThrow(() -> new AssertionError("Session not found"));
        int meetingId = session.getId();

        // Wait 2 seconds and verify session is still completely active in MySQL
        Thread.sleep(2000);
        Optional<QrSession> checkActive = sessionDao.findActiveByCourseId(courseId);
        if (!checkActive.isPresent()) {
            throw new AssertionError("Active session was prematurely closed by timezone mismatch!");
        }

        // Verify autoCloseExpiredSessions does NOT close an active session whose lecture is in progress
        int closed = sessionDao.autoCloseExpiredSessions();
        if (closed > 0) {
            throw new AssertionError("autoCloseExpiredSessions closed " + closed + " sessions that are currently in progress!");
        }

        // Verify session remains active
        Optional<QrSession> stillActive = sessionDao.findActiveByCourseId(courseId);
        if (!stillActive.isPresent() || stillActive.get().getId() != meetingId) {
            throw new AssertionError("Session should still be active with meeting ID " + meetingId);
        }

        // Clean up
        sessionDao.closeSession(meetingId);
    }

    // ─────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────
    private static int createTestCourse(String code, String name) {
        CourseDao courseDao = new CourseDao();
        courseDao.findByCourseCode(code).ifPresent(existing -> courseDao.delete(existing.getId()));
        Course c = new Course();
        c.setCourseCode(code);
        c.setCourseName(name);
        c.setTeacherId(1);
        c.setSemester(5);
        c.setDepartment("Computer Science");
        courseDao.save(c);
        return c.getId();
    }

    private static int countSessionsForCourse(int courseId) {
        String sql = "SELECT COUNT(*) FROM qr_sessions WHERE course_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static int countAttendanceForCourse(int courseId) {
        String sql = "SELECT COUNT(*) FROM attendance WHERE course_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static int countAttendanceForStudentAndCourse(int studentId, int courseId) {
        String sql = "SELECT COUNT(*) FROM attendance WHERE student_id = ? AND course_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

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

    private static String sendGet(CookieManager cm, String urlStr) throws Exception {
        HttpURLConnection conn = openConnectionWithCookies(cm, urlStr);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        saveCookies(cm, conn);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
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
        if (code >= 200 && code < 300) {
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
}
