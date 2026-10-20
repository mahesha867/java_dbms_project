package com.attendance.model;

import java.time.LocalDateTime;

/**
 * Teacher entity.
 * Demonstrates:
 * - Inheritance (extends User)
 * - Polymorphism (overrides getDashboardPath(), getRoleInfo())
 * - Encapsulation (private fields with public getters/setters)
 */
public class Teacher extends User {

    private int teacherId;
    private String employeeId;
    private String department;

    public Teacher() {
        super();
        setRole("TEACHER");
    }

    public Teacher(int id, String name, String email, String password,
                   String phone, LocalDateTime createdAt,
                   int teacherId, String employeeId, String department) {
        super(id, name, email, password, "TEACHER", phone, createdAt);
        this.teacherId  = teacherId;
        this.employeeId = employeeId;
        this.department = department;
    }

    // ── Polymorphic methods overridden from User ──────────────────
    @Override
    public String getDashboardPath() {
        return "/teacher/dashboard";
    }

    @Override
    public String getRoleInfo() {
        return "Teacher ID: " + employeeId + " | Department: " + department;
    }

    // ── Getters and Setters (Encapsulation) ────────────────────────
    public int getTeacherId()                    { return teacherId; }
    public void setTeacherId(int teacherId)      { this.teacherId = teacherId; }

    public String getEmployeeId()                { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

    public String getDepartment()                { return department; }
    public void setDepartment(String department) { this.department = department; }

    @Override
    public String toString() {
        return "Teacher{" +
                "teacherId=" + teacherId +
                ", employeeId='" + employeeId + '\'' +
                ", department='" + department + '\'' +
                ", user=" + super.toString() +
                '}';
    }
}
