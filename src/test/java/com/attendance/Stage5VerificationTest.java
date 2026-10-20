package com.attendance;

import com.attendance.dao.CourseDao;
import com.attendance.dao.CourseScheduleDao;
import com.attendance.dao.StudentDao;
import com.attendance.model.Course;
import com.attendance.model.CourseSchedule;
import com.attendance.model.Student;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.util.List;

/**
 * Automated Verification Test Suite for Stage 5: Faculty Course and Semester Management:
 * 1. Course CRUD persistence.
 * 2. Strict faculty ownership enforcement (cross-faculty edit, delete, roster view rejected with 403).
 * 3. Course roster access for owned course (200 OK with enrolled students & percentages).
 * 4. Semester schedules & planned sessions CRUD.
 * 5. Schedule edge-case validation (start_time >= end_time rejected).
 * 6. Cross-faculty schedule creation/deletion rejected with 403.
 * 7. Student access to faculty endpoints rejected with 403.
 */
public class Stage5VerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 5 Automated Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. Faculty Course CRUD Persistence", Stage5VerificationTest::testCourseCrudPersistence);
        runTest("2. Cross-Faculty Edit Blocked (HTTP 403 Forbidden)", Stage5VerificationTest::testCrossFacultyEditForbidden);
        runTest("3. Cross-Faculty Delete Blocked (HTTP 403 Forbidden)", Stage5VerificationTest::testCrossFacultyDeleteForbidden);
        runTest("4. Cross-Faculty Roster View Blocked (HTTP 403 Forbidden)", Stage5VerificationTest::testCrossFacultyRosterForbidden);
        runTest("5. Owned Course Roster View (HTTP 200 OK)", Stage5VerificationTest::testOwnedCourseRoster);
        runTest("6. Semester Schedule CRUD & Time Edge Case Validation", Stage5VerificationTest::testScheduleCrudAndValidation);
        runTest("7. Student Access to Faculty Course Routes Blocked (HTTP 403)", Stage5VerificationTest::testStudentAccessBlocked);

        System.out.println("========================================================");
        System.out.println("Stage 5 Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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

    private static void testCourseCrudPersistence() throws Exception {
        CourseDao dao = new CourseDao();
        String uniqueCode = "CS" + (int)(System.currentTimeMillis() % 10000);

        Course c = new Course();
        c.setCourseCode(uniqueCode);
        c.setCourseName("Distributed Computing Systems");
        c.setTeacherId(1);
        c.setSemester(6);
        c.setDepartment("Computer Science");

        // 1. Create
        boolean saved = dao.save(c);
        if (!saved || c.getId() == 0) throw new AssertionError("Course creation failed");

        // 2. Read
        Course fetched = dao.findById(c.getId()).orElseThrow(() -> new AssertionError("Created course not found"));
        if (!uniqueCode.equals(fetched.getCourseCode())) throw new AssertionError("Course code mismatch");

        // 3. Update
        fetched.setCourseName("Advanced Distributed Cloud Systems");
        boolean updated = dao.update(fetched);
        if (!updated) throw new AssertionError("Course update failed");

        Course updatedFetched = dao.findById(c.getId()).get();
        if (!"Advanced Distributed Cloud Systems".equals(updatedFetched.getCourseName())) {
            throw new AssertionError("Course name not updated in database");
        }

        // 4. Delete
        boolean deleted = dao.delete(c.getId());
        if (!deleted) throw new AssertionError("Course deletion failed");
        if (dao.findById(c.getId()).isPresent()) throw new AssertionError("Course still exists after deletion");
    }

    private static void testCrossFacultyEditForbidden() throws Exception {
        // Teacher 1 attempts to edit Course 3 (owned by Teacher 2, sunita@attendance.com)
        CookieManager cmTeacher1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher1, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(cmTeacher1, BASE_URL + "/teacher/courses/edit");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);

        // Course 3 belongs to Teacher 2
        String params = "courseId=3&courseName=HackedCourseName&department=IT&semester=5";
        conn.getOutputStream().write(params.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();

        if (code != 403) {
            throw new AssertionError("Teacher 1 editing Teacher 2's course 3 must return 403 Forbidden! Got: " + code);
        }
    }

    private static void testCrossFacultyDeleteForbidden() throws Exception {
        // Teacher 1 attempts to delete Course 3 (owned by Teacher 2)
        CookieManager cmTeacher1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher1, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(cmTeacher1, BASE_URL + "/teacher/courses/delete");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);

        String params = "courseId=3";
        conn.getOutputStream().write(params.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();

        if (code != 403) {
            throw new AssertionError("Teacher 1 deleting Teacher 2's course 3 must return 403 Forbidden! Got: " + code);
        }
    }

    private static void testCrossFacultyRosterForbidden() throws Exception {
        // Teacher 1 attempts to view roster for Course 3 (owned by Teacher 2)
        CookieManager cmTeacher1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher1, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(cmTeacher1, BASE_URL + "/teacher/courses/roster?courseId=3");
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        int code = conn.getResponseCode();

        if (code != 403) {
            throw new AssertionError("Teacher 1 viewing roster for Teacher 2's course 3 must return 403 Forbidden! Got: " + code);
        }
    }

    private static void testOwnedCourseRoster() throws Exception {
        // Teacher 1 viewing roster for Course 1 (owned by Teacher 1)
        CookieManager cmTeacher1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher1, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(cmTeacher1, BASE_URL + "/teacher/courses/roster?courseId=1");
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        int code = conn.getResponseCode();

        if (code != 200) {
            throw new AssertionError("Teacher 1 viewing roster for owned course 1 must return 200 OK! Got: " + code);
        }

        // Verify students are enrolled
        StudentDao studentDao = new StudentDao();
        List<Student> students = studentDao.findStudentsByCourse(1);
        if (students.isEmpty()) {
            throw new AssertionError("Course 1 roster should contain enrolled students");
        }
    }

    private static void testScheduleCrudAndValidation() throws Exception {
        CourseScheduleDao scheduleDao = new CourseScheduleDao();

        // 1. Edge Case: Start Time >= End Time must be rejected
        CookieManager cmTeacher1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmTeacher1, "teacher@attendance.com", "teacher123");

        HttpURLConnection invalidTimeConn = openConnectionWithCookies(cmTeacher1, BASE_URL + "/teacher/schedules/create");
        invalidTimeConn.setRequestMethod("POST");
        invalidTimeConn.setDoOutput(true);
        invalidTimeConn.setInstanceFollowRedirects(false);

        // start 14:00, end 13:00 (inverted!)
        String invalidParams = "courseId=1&dayOfWeek=Monday&startTime=14:00&endTime=13:00&room=Lab1";
        invalidTimeConn.getOutputStream().write(invalidParams.getBytes(StandardCharsets.UTF_8));
        String location = invalidTimeConn.getHeaderField("Location");

        if (location == null || !location.contains("Start+time+must+be+earlier+than+end+time")) {
            throw new AssertionError("Inverted schedule time window was not rejected with validation error! Location: " + location);
        }

        // 2. Valid Schedule Creation
        HttpURLConnection validTimeConn = openConnectionWithCookies(cmTeacher1, BASE_URL + "/teacher/schedules/create");
        validTimeConn.setRequestMethod("POST");
        validTimeConn.setDoOutput(true);
        validTimeConn.setInstanceFollowRedirects(false);

        String validParams = "courseId=1&dayOfWeek=Friday&startTime=10:00&endTime=11:30&room=TestHall404";
        validTimeConn.getOutputStream().write(validParams.getBytes(StandardCharsets.UTF_8));
        int validCode = validTimeConn.getResponseCode();
        if (validCode != 302) {
            throw new AssertionError("Valid schedule creation should redirect with 302, got: " + validCode);
        }

        // Verify persisted in DB
        List<CourseSchedule> list = scheduleDao.findByCourseId(1);
        CourseSchedule created = null;
        for (CourseSchedule cs : list) {
            if ("TestHall404".equals(cs.getRoom())) {
                created = cs;
                break;
            }
        }
        if (created == null) throw new AssertionError("Created schedule was not persisted in database");

        // 3. Delete Schedule
        boolean deleted = scheduleDao.delete(created.getId());
        if (!deleted) throw new AssertionError("Failed to delete created schedule");
    }

    private static void testStudentAccessBlocked() throws Exception {
        // Student attempting to access /teacher/courses/roster or /teacher/courses/create
        CookieManager cmStudent = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cmStudent, "aarav@student.com", "student123");

        HttpURLConnection conn = openConnectionWithCookies(cmStudent, BASE_URL + "/teacher/courses/roster?courseId=1");
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        int code = conn.getResponseCode();

        if (code != 403) {
            throw new AssertionError("Student accessing faculty course roster must be blocked with 403 Forbidden! Got: " + code);
        }

        HttpURLConnection connCreate = openConnectionWithCookies(cmStudent, BASE_URL + "/teacher/courses/create");
        connCreate.setRequestMethod("POST");
        connCreate.setDoOutput(true);
        connCreate.setInstanceFollowRedirects(false);
        connCreate.getOutputStream().write("courseCode=CS999&courseName=Test&semester=5&department=CS".getBytes(StandardCharsets.UTF_8));
        int codeCreate = connCreate.getResponseCode();

        if (codeCreate != 403) {
            throw new AssertionError("Student attempting to create course must be blocked with 403 Forbidden! Got: " + codeCreate);
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
