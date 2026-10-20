package com.attendance.model;

import java.time.LocalDateTime;

/**
 * Student entity.
 * Demonstrates:
 * - Inheritance (extends User)
 * - Polymorphism (overrides getDashboardPath(), getRoleInfo(), getIdentifier())
 * - Interface Implementation (implements Attendable)
 * - Encapsulation (private fields with public getters/setters)
 */
public class Student extends User implements Attendable {

    private int studentId;
    private String rollNumber;
    private String department;
    private int semester;

    public Student() {
        super();
        setRole("STUDENT");
    }

    public Student(int id, String name, String email, String password,
                   String phone, LocalDateTime createdAt,
                   int studentId, String rollNumber, String department, int semester) {
        super(id, name, email, password, "STUDENT", phone, createdAt);
        this.studentId  = studentId;
        this.rollNumber = rollNumber;
        this.department = department;
        this.semester   = semester;
    }

    // ── Polymorphic methods overridden from User ──────────────────
    @Override
    public String getDashboardPath() {
        return "/student/dashboard";
    }

    @Override
    public String getRoleInfo() {
        return "Student Roll No: " + rollNumber + " | Department: " + department + " (Sem " + semester + ")";
    }

    // ── Implemented methods from Attendable Interface ─────────────
    @Override
    public boolean canMarkAttendance(String sessionToken) {
        return sessionToken != null && !sessionToken.trim().isEmpty() && studentId > 0;
    }

    @Override
    public String getIdentifier() {
        return this.rollNumber;
    }

    // ── Getters and Setters (Encapsulation) ────────────────────────
    public int getStudentId()                    { return studentId; }
    public void setStudentId(int studentId)      { this.studentId = studentId; }

    public String getRollNumber()                { return rollNumber; }
    public void setRollNumber(String rollNumber) { this.rollNumber = rollNumber; }

    public String getDepartment()                { return department; }
    public void setDepartment(String department) { this.department = department; }

    public int getSemester()                     { return semester; }
    public void setSemester(int semester)        { this.semester = semester; }

    @Override
    public String toString() {
        return "Student{" +
                "studentId=" + studentId +
                ", rollNumber='" + rollNumber + '\'' +
                ", department='" + department + '\'' +
                ", semester=" + semester +
                ", user=" + super.toString() +
                '}';
    }
}
