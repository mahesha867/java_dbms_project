package com.attendance.servlet;

import com.attendance.dao.AttendanceDao;
import com.attendance.dao.CourseDao;
import com.attendance.dao.TeacherDao;
import com.attendance.model.Attendance;
import com.attendance.model.Course;
import com.attendance.model.Teacher;
import com.attendance.model.User;
import com.attendance.util.SessionUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Servlet handling manual attendance overrides and OD / Excused status marking
 * with transactional consistency and immutable audit history logging.
 */
@WebServlet(name = "AttendanceManualServlet", urlPatterns = {"/teacher/attendance/update", "/teacher/attendance/create"})
public class AttendanceManualServlet extends HttpServlet {

    private static final Set<String> ALLOWED_STATUSES = new HashSet<>(
            Arrays.asList("PRESENT", "ABSENT", "LATE", "OD", "EXCUSED")
    );

    private AttendanceDao attendanceDao;
    private CourseDao courseDao;
    private TeacherDao teacherDao;

    @Override
    public void init() throws ServletException {
        this.attendanceDao = new AttendanceDao();
        this.courseDao = new CourseDao();
        this.teacherDao = new TeacherDao();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        handleRequest(request, response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        handleRequest(request, response);
    }

    private void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        User user = SessionUtil.getLoggedInUser(request);
        if (user == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Please log in.");
            return;
        }

        if (!"TEACHER".equalsIgnoreCase(user.getRole())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access Denied: Manual attendance correction is restricted to faculty only.");
            return;
        }

        Teacher teacher = SessionUtil.getTeacherProfile(request);
        if (teacher == null) {
            teacher = teacherDao.findByUserId(user.getId()).orElse(null);
        }

        String path = request.getServletPath();
        String format = request.getParameter("format");
        boolean isJson = "json".equalsIgnoreCase(format) || 
                         (request.getContentType() != null && request.getContentType().contains("application/json"));

        String statusParam = request.getParameter("status");
        String reasonParam = request.getParameter("reason");

        if (statusParam == null || !ALLOWED_STATUSES.contains(statusParam.trim().toUpperCase())) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid status. Allowed values: PRESENT, ABSENT, LATE, OD, EXCUSED", isJson);
            return;
        }

        if (reasonParam == null || reasonParam.trim().length() < 3) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "Reason is mandatory for manual corrections (minimum 3 characters).", isJson);
            return;
        }

        String status = statusParam.trim().toUpperCase();
        String reason = reasonParam.trim();

        if ("/teacher/attendance/update".equals(path)) {
            String attendanceIdParam = request.getParameter("attendanceId");
            if (attendanceIdParam == null || attendanceIdParam.trim().isEmpty()) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "attendanceId is required.", isJson);
                return;
            }

            int attendanceId;
            try {
                attendanceId = Integer.parseInt(attendanceIdParam.trim());
            } catch (NumberFormatException e) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid attendanceId.", isJson);
                return;
            }

            Optional<Attendance> attOpt = attendanceDao.findById(attendanceId);
            if (!attOpt.isPresent()) {
                sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "Attendance record not found.", isJson);
                return;
            }

            Attendance att = attOpt.get();
            Optional<Course> courseOpt = courseDao.findById(att.getCourseId());
            if (!courseOpt.isPresent()) {
                sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "Associated course not found.", isJson);
                return;
            }

            Course course = courseOpt.get();
            if (teacher != null && course.getTeacherId() != teacher.getTeacherId()) {
                sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "Access Denied: You do not own this course.", isJson);
                return;
            }

            try {
                boolean success = attendanceDao.updateWithAuditTransaction(attendanceId, status, reason, user.getId());
                if (success) {
                    if (isJson) {
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        try (PrintWriter out = response.getWriter()) {
                            out.print("{\"status\":\"ok\",\"message\":\"Attendance updated successfully\",\"newStatus\":\"" + status + "\"}");
                        }
                    } else {
                        response.sendRedirect(request.getContextPath() + "/reports?courseId=" + att.getCourseId() + "&msg=status_updated");
                    }
                } else {
                    sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to update attendance.", isJson);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database error: " + e.getMessage(), isJson);
            }
            return;
        }

        if ("/teacher/attendance/create".equals(path)) {
            String studentIdParam = request.getParameter("studentId");
            String courseIdParam = request.getParameter("courseId");
            String sessionIdParam = request.getParameter("sessionId");

            if (studentIdParam == null || courseIdParam == null || sessionIdParam == null) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "studentId, courseId, and sessionId are required.", isJson);
                return;
            }

            try {
                int studentId = Integer.parseInt(studentIdParam.trim());
                int courseId = Integer.parseInt(courseIdParam.trim());
                int sessionId = Integer.parseInt(sessionIdParam.trim());

                Optional<Course> courseOpt = courseDao.findById(courseId);
                if (!courseOpt.isPresent()) {
                    sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "Course not found.", isJson);
                    return;
                }

                if (teacher != null && courseOpt.get().getTeacherId() != teacher.getTeacherId()) {
                    sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "Access Denied: You do not own this course.", isJson);
                    return;
                }

                boolean success = attendanceDao.createWithAuditTransaction(studentId, courseId, sessionId, status, reason, user.getId());
                if (success) {
                    if (isJson) {
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        try (PrintWriter out = response.getWriter()) {
                            out.print("{\"status\":\"ok\",\"message\":\"Attendance recorded successfully\",\"status\":\"" + status + "\"}");
                        }
                    } else {
                        response.sendRedirect(request.getContextPath() + "/reports?courseId=" + courseId + "&msg=attendance_created");
                    }
                } else {
                    sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create attendance.", isJson);
                }
            } catch (NumberFormatException e) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid numeric parameters.", isJson);
            } catch (SQLException e) {
                e.printStackTrace();
                sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database error: " + e.getMessage(), isJson);
            }
        }
    }

    private void sendErrorResponse(HttpServletResponse response, int statusCode, String message, boolean isJson) throws IOException {
        if (isJson) {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            try (PrintWriter out = response.getWriter()) {
                out.print("{\"status\":\"error\",\"statusCode\":" + statusCode + ",\"message\":\"" + message.replace("\"", "\\\"") + "\"}");
            }
        } else {
            response.sendError(statusCode, message);
        }
    }
}
