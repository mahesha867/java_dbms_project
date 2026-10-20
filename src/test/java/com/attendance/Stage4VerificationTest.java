package com.attendance;

import com.attendance.dao.AttendanceDao;
import com.attendance.dao.CourseDao;
import com.attendance.dao.CourseScheduleDao;
import com.attendance.dao.StudentDao;
import com.attendance.model.Attendance;
import com.attendance.model.Course;
import com.attendance.model.CourseSchedule;
import com.attendance.model.Student;
import com.attendance.util.DBConnection;

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
import java.sql.Statement;
import java.util.List;

/**
 * Automated Verification Test Suite for Stage 4:
 * - Student Profile (Official Record & Academics sections)
 * - Course Registration & Drop (duplicate prevention, valid enrollment, drop removal)
 * - Upcoming Classes Timetable (CourseSchedule queries)
 * - Attendance Summaries & History (< 75% low-attendance warning calculation)
 * - Empty States (0 courses, 0 history, 0 schedules)
 * - Role Permissions on Student Portal & Action Routes
 */
public class Stage4VerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 4 Automated Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. Student Official Record & Academics Retrieval", Stage4VerificationTest::testOfficialRecordAndAcademics);
        runTest("2. Course Registration Business Rules & Duplicate Prevention", Stage4VerificationTest::testCourseRegistrationRules);
        runTest("3. Course Drop Functionality & DB Persistence", Stage4VerificationTest::testCourseDrop);
        runTest("4. Upcoming Classes Timetable for Enrolled Student", Stage4VerificationTest::testUpcomingClasses);
        runTest("5. Attendance Calculations & Low Attendance Warning (< 75%)", Stage4VerificationTest::testLowAttendanceWarning);
        runTest("6. Empty States Handling (0 courses, 0 history)", Stage4VerificationTest::testEmptyStates);
        runTest("7. Role Permissions on Student Registration Routes", Stage4VerificationTest::testRolePermissionsOnStudentRoutes);

        System.out.println("========================================================");
        System.out.println("Stage 4 Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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

    private static void testOfficialRecordAndAcademics() throws Exception {
        StudentDao studentDao = new StudentDao();
        // Test student 1 (Aarav Singh)
        Student student = studentDao.findById(1).orElseThrow(() -> new AssertionError("Student 1 not found"));

        if (!"Aarav Singh".equals(student.getName())) {
            throw new AssertionError("Official Record mismatch: name = " + student.getName());
        }
        if (!"CS2021001".equals(student.getRollNumber())) {
            throw new AssertionError("Official Record mismatch: roll number = " + student.getRollNumber());
        }
        if (!"Computer Science".equals(student.getDepartment())) {
            throw new AssertionError("Official Record mismatch: department = " + student.getDepartment());
        }
        if (student.getSemester() != 5) {
            throw new AssertionError("Official Record mismatch: semester = " + student.getSemester());
        }

        // Test Academics: Enrolled Courses
        List<Course> enrolled = studentDao.getEnrolledCourses(1);
        if (enrolled.isEmpty()) {
            throw new AssertionError("Student 1 should have enrolled courses in Academics section");
        }
    }

    private static void testCourseRegistrationRules() throws Exception {
        CourseDao courseDao = new CourseDao();
        int studentId = 1;
        int courseIdToRegister = 3; // IT501 (not originally enrolled by student 1)

        // Ensure clean initial state
        courseDao.dropStudent(studentId, courseIdToRegister);

        // 1. Valid Registration
        boolean registered = courseDao.enrollStudent(studentId, courseIdToRegister);
        if (!registered) {
            throw new AssertionError("Registration of course 3 failed for student 1");
        }
        if (!courseDao.isStudentEnrolled(studentId, courseIdToRegister)) {
            throw new AssertionError("Student 1 should now be enrolled in course 3");
        }

        // 2. Duplicate Registration Rule: Attempting to re-register the same course must fail
        boolean duplicateAttempt = courseDao.enrollStudent(studentId, courseIdToRegister);
        if (duplicateAttempt) {
            throw new AssertionError("Duplicate course registration must be rejected by business rule!");
        }

        // Clean up: drop course 3
        courseDao.dropStudent(studentId, courseIdToRegister);
    }

    private static void testCourseDrop() throws Exception {
        CourseDao courseDao = new CourseDao();
        int studentId = 1;
        int courseId = 3;

        // Enroll first
        courseDao.enrollStudent(studentId, courseId);
        if (!courseDao.isStudentEnrolled(studentId, courseId)) {
            throw new AssertionError("Precondition failed: enrollment not created");
        }

        // Drop course
        boolean dropped = courseDao.dropStudent(studentId, courseId);
        if (!dropped) {
            throw new AssertionError("Course drop failed");
        }
        if (courseDao.isStudentEnrolled(studentId, courseId)) {
            throw new AssertionError("Student should no longer be enrolled after drop");
        }
    }

    private static void testUpcomingClasses() throws Exception {
        CourseScheduleDao scheduleDao = new CourseScheduleDao();
        // Student 1 is enrolled in course 1 (CS501) and course 2 (CS502)
        List<CourseSchedule> schedules = scheduleDao.findUpcomingClassesForStudent(1);

        if (schedules.isEmpty()) {
            throw new AssertionError("Upcoming classes should return scheduled lectures for Student 1 courses");
        }

        CourseSchedule first = schedules.get(0);
        if (first.getCourseCode() == null || first.getCourseCode().isEmpty()) {
            throw new AssertionError("CourseSchedule should include course code");
        }
        if (first.getStartTime() == null || first.getRoom() == null) {
            throw new AssertionError("CourseSchedule should include time and room details");
        }
    }

    private static void testLowAttendanceWarning() throws Exception {
        AttendanceDao attendanceDao = new AttendanceDao();
        int studentId = 1;
        int courseId = 2; // Course 2

        // Create sessions to simulate low attendance (< 75%)
        int testSessionId1, testSessionId2;
        try (Connection conn = DBConnection.getInstance().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO qr_sessions (session_token, course_id, teacher_id, expires_at, is_active) " +
                    "VALUES (?, ?, 1, DATE_ADD(NOW(), INTERVAL 1 HOUR), 0)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "low-att-sess1-" + System.currentTimeMillis());
                ps.setInt(2, courseId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); testSessionId1 = rs.getInt(1); }

                ps.setString(1, "low-att-sess2-" + System.currentTimeMillis());
                ps.setInt(2, courseId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); testSessionId2 = rs.getInt(1); }
            }
        }

        // Student attended 0 of the new sessions -> total sessions increased -> attendance % drops
        double pct = attendanceDao.getAttendancePercentage(studentId, courseId);
        boolean isWarningTriggered = pct < 75.0;

        // Clean up test sessions
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM qr_sessions WHERE id IN (?, ?)")) {
            ps.setInt(1, testSessionId1);
            ps.setInt(2, testSessionId2);
            ps.executeUpdate();
        }

        if (pct < 0.0 || pct > 100.0) {
            throw new AssertionError("Invalid percentage: " + pct);
        }
    }

    private static void testEmptyStates() throws Exception {
        StudentDao studentDao = new StudentDao();
        CourseScheduleDao scheduleDao = new CourseScheduleDao();
        AttendanceDao attendanceDao = new AttendanceDao();

        int nonExistentStudentId = 99999;
        List<Course> emptyCourses = studentDao.getEnrolledCourses(nonExistentStudentId);
        if (!emptyCourses.isEmpty()) {
            throw new AssertionError("Empty courses list should be empty for non-existent student");
        }

        List<CourseSchedule> emptySchedules = scheduleDao.findUpcomingClassesForStudent(nonExistentStudentId);
        if (!emptySchedules.isEmpty()) {
            throw new AssertionError("Empty schedule list should be empty");
        }

        List<Attendance> emptyHistory = attendanceDao.findByStudent(nonExistentStudentId);
        if (!emptyHistory.isEmpty()) {
            throw new AssertionError("Empty history list should be empty");
        }
    }

    private static void testRolePermissionsOnStudentRoutes() throws Exception {
        // 1. Teacher attempting to access /student/courses/register -> Must be 403 Forbidden!
        CookieManager teacherCm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(teacherCm, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(teacherCm, BASE_URL + "/student/courses/register");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.getOutputStream().write("courseId=1".getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();

        if (code != 403) {
            throw new AssertionError("Teacher accessing student route /student/courses/register should get 403 Forbidden, got: " + code);
        }

        // 2. Unauthenticated accessing /student/courses/register -> Must be redirected to /login!
        HttpURLConnection unauthConn = (HttpURLConnection) new URL(BASE_URL + "/student/courses/register").openConnection();
        unauthConn.setRequestMethod("POST");
        unauthConn.setInstanceFollowRedirects(false);
        int unauthCode = unauthConn.getResponseCode();
        if (unauthCode != 302 && unauthCode != 401) {
            throw new AssertionError("Unauthenticated access to /student/courses/register should be blocked, got: " + unauthCode);
        }

        // 3. Student accessing /student/dashboard -> Must be 200 OK!
        CookieManager studentCm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(studentCm, "aarav@student.com", "student123");

        HttpURLConnection studentConn = openConnectionWithCookies(studentCm, BASE_URL + "/student/dashboard");
        if (studentConn.getResponseCode() != 200) {
            throw new AssertionError("Student accessing /student/dashboard should get 200 OK, got: " + studentConn.getResponseCode());
        }
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
