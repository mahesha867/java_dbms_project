package com.attendance;

import com.attendance.util.PasswordUtil;
import org.mindrot.jbcrypt.BCrypt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Automated Verification Test Suite for Stage 3:
 * 1. BCrypt password verification and legacy hash auto-upgrade path.
 * 2. Unauthenticated access enforcement across all protected routes.
 * 3. Student-to-Faculty access rejection (HTTP 403 Forbidden).
 * 4. Faculty-to-Student access rejection (HTTP 403 Forbidden).
 * 5. Faculty-to-Admin access rejection (HTTP 403 Forbidden).
 * 6. Allowed role-specific access (HTTP 200 OK).
 */
public class Stage3VerificationTest {

    private static final String BASE_URL = "http://localhost:8080/attendance";
    private static int totalPassed = 0;
    private static int totalFailed = 0;

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("   Stage 3 Automated Verification Test Suite");
        System.out.println("========================================================");

        runTest("1. BCrypt Password Hashing & Verification", Stage3VerificationTest::testBcryptPasswordHashing);
        runTest("2. Legacy SHA-256 Migration Path Verification", Stage3VerificationTest::testLegacyMigrationPath);
        runTest("3. Unauthenticated Access Blocked (302 Redirect to /login)", Stage3VerificationTest::testUnauthenticatedAccess);
        runTest("4. Student-to-Faculty Access Blocked (HTTP 403 Forbidden)", Stage3VerificationTest::testStudentToFacultyForbidden);
        runTest("5. Faculty-to-Student Access Blocked (HTTP 403 Forbidden)", Stage3VerificationTest::testFacultyToStudentForbidden);
        runTest("6. Faculty-to-Admin Access Blocked (HTTP 403 Forbidden)", Stage3VerificationTest::testFacultyToAdminForbidden);
        runTest("7. Allowed Role Access (HTTP 200 OK)", Stage3VerificationTest::testAllowedRoleAccess);

        System.out.println("========================================================");
        System.out.println("Stage 3 Tests Passed: " + totalPassed + " | Tests Failed: " + totalFailed);
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

    private static void testBcryptPasswordHashing() throws Exception {
        String raw = "samplePassword456";
        String hashed = PasswordUtil.hashPassword(raw);

        if (!hashed.startsWith("$2a$")) {
            throw new AssertionError("Hashed password must start with BCrypt identifier $2a$: " + hashed);
        }
        if (!PasswordUtil.verifyPassword(raw, hashed)) {
            throw new AssertionError("Correct password failed BCrypt verification");
        }
        if (PasswordUtil.verifyPassword("wrongPassword", hashed)) {
            throw new AssertionError("Incorrect password passed BCrypt verification");
        }
        if (PasswordUtil.needsUpgrade(hashed)) {
            throw new AssertionError("Valid BCrypt hash should not be flagged for upgrade");
        }
    }

    private static void testLegacyMigrationPath() throws Exception {
        // Known legacy SHA-256 hash for 'student123'
        String legacySha256 = PasswordUtil.hashTokenSha256("student123");
        if (legacySha256.length() != 64) {
            throw new AssertionError("Legacy SHA-256 hash should be 64 characters");
        }
        if (!PasswordUtil.verifyPassword("student123", legacySha256)) {
            throw new AssertionError("Legacy SHA-256 verification failed during migration check");
        }
        if (!PasswordUtil.needsUpgrade(legacySha256)) {
            throw new AssertionError("Legacy SHA-256 hash MUST be flagged for upgrade");
        }
    }

    private static void testUnauthenticatedAccess() throws Exception {
        String[] protectedRoutes = {
            "/teacher/dashboard",
            "/student/dashboard",
            "/reports",
            "/qr/generate?courseId=1"
        };

        for (String route : protectedRoutes) {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + route).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (code != 302 || location == null || !location.contains("/login")) {
                throw new AssertionError("Unauthenticated access to " + route + " did not redirect to /login. Code: " + code + ", Location: " + location);
            }
        }
    }

    private static void testStudentToFacultyForbidden() throws Exception {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "aarav@student.com", "student123");

        String[] facultyRoutes = {
            "/teacher/dashboard",
            "/qr/generate?courseId=1",
            "/reports",
            "/qr/active"
        };

        for (String route : facultyRoutes) {
            HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + route);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code != 403) {
                throw new AssertionError("Student accessing faculty route " + route + " was not blocked with 403 Forbidden! Got: " + code);
            }
        }
    }

    private static void testFacultyToStudentForbidden() throws Exception {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        String[] studentRoutes = {
            "/student/dashboard",
            "/qr/scan"
        };

        for (String route : studentRoutes) {
            HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + route);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code != 403) {
                throw new AssertionError("Faculty accessing student route " + route + " was not blocked with 403 Forbidden! Got: " + code);
            }
        }
    }

    private static void testFacultyToAdminForbidden() throws Exception {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(cm, "teacher@attendance.com", "teacher123");

        HttpURLConnection conn = openConnectionWithCookies(cm, BASE_URL + "/admin/settings");
        conn.setInstanceFollowRedirects(false);
        int code = conn.getResponseCode();
        if (code != 403) {
            throw new AssertionError("Faculty accessing admin route /admin/settings was not blocked with 403 Forbidden! Got: " + code);
        }
    }

    private static void testAllowedRoleAccess() throws Exception {
        // 1. Teacher accessing teacher dashboard and reports
        CookieManager teacherCm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(teacherCm, "teacher@attendance.com", "teacher123");

        HttpURLConnection teacherConn = openConnectionWithCookies(teacherCm, BASE_URL + "/teacher/dashboard");
        if (teacherConn.getResponseCode() != 200) {
            throw new AssertionError("Teacher should access /teacher/dashboard with 200, got: " + teacherConn.getResponseCode());
        }

        HttpURLConnection reportsConn = openConnectionWithCookies(teacherCm, BASE_URL + "/reports");
        if (reportsConn.getResponseCode() != 200) {
            throw new AssertionError("Teacher should access /reports with 200, got: " + reportsConn.getResponseCode());
        }

        // 2. Student accessing student dashboard
        CookieManager studentCm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        login(studentCm, "aarav@student.com", "student123");

        HttpURLConnection studentConn = openConnectionWithCookies(studentCm, BASE_URL + "/student/dashboard");
        if (studentConn.getResponseCode() != 200) {
            throw new AssertionError("Student should access /student/dashboard with 200, got: " + studentConn.getResponseCode());
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
