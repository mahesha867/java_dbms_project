package com.attendance.dao;

import com.attendance.model.Teacher;
import com.attendance.util.DBConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Teachers.
 * Demonstrates:
 * - Implements Dao<Teacher>
 * - Multi-table SQL joins (teachers JOIN users)
 */
public class TeacherDao implements Dao<Teacher> {

    @Override
    public Optional<Teacher> findById(int teacherId) {
        String sql = "SELECT t.*, u.name, u.email, u.password, u.phone, u.created_at " +
                     "FROM teachers t " +
                     "JOIN users u ON t.user_id = u.id " +
                     "WHERE t.teacher_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teacherId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToTeacher(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public Optional<Teacher> findByUserId(int userId) {
        String sql = "SELECT t.*, u.name, u.email, u.password, u.phone, u.created_at " +
                     "FROM teachers t " +
                     "JOIN users u ON t.user_id = u.id " +
                     "WHERE t.user_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToTeacher(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public Optional<Teacher> findByEmployeeId(String employeeId) {
        String sql = "SELECT t.*, u.name, u.email, u.password, u.phone, u.created_at " +
                     "FROM teachers t " +
                     "JOIN users u ON t.user_id = u.id " +
                     "WHERE t.employee_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, employeeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToTeacher(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    @Override
    public List<Teacher> findAll() {
        List<Teacher> teachers = new ArrayList<>();
        String sql = "SELECT t.*, u.name, u.email, u.password, u.phone, u.created_at " +
                     "FROM teachers t " +
                     "JOIN users u ON t.user_id = u.id " +
                     "ORDER BY t.teacher_id ASC";
        try (Connection conn = DBConnection.getInstance().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                teachers.add(mapResultSetToTeacher(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return teachers;
    }

    @Override
    public boolean save(Teacher teacher) {
        String sql = "INSERT INTO teachers (user_id, employee_id, department) VALUES (?, ?, ?)";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, teacher.getId()); // user_id
            ps.setString(2, teacher.getEmployeeId());
            ps.setString(3, teacher.getDepartment());
            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        teacher.setTeacherId(keys.getInt(1));
                    }
                }
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean update(Teacher teacher) {
        String sql = "UPDATE teachers SET employee_id = ?, department = ? WHERE teacher_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teacher.getEmployeeId());
            ps.setString(2, teacher.getDepartment());
            ps.setInt(3, teacher.getTeacherId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean delete(int teacherId) {
        String sql = "DELETE FROM teachers WHERE teacher_id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, teacherId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private Teacher mapResultSetToTeacher(ResultSet rs) throws SQLException {
        Teacher t = new Teacher();
        t.setTeacherId(rs.getInt("teacher_id"));
        t.setId(rs.getInt("user_id"));
        t.setEmployeeId(rs.getString("employee_id"));
        t.setDepartment(rs.getString("department"));
        t.setName(rs.getString("name"));
        t.setEmail(rs.getString("email"));
        t.setPassword(rs.getString("password"));
        t.setPhone(rs.getString("phone"));
        t.setRole("TEACHER");
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) {
            t.setCreatedAt(ts.toLocalDateTime());
        }
        return t;
    }
}
