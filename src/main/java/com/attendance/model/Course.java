package com.attendance.model;

import java.time.LocalDateTime;

/**
 * Course entity representing an academic subject/module.
 * Demonstrates: Encapsulation
 */
public class Course {

    private int id;
    private String courseCode;
    private String courseName;
    private int teacherId;
    private String teacherName;
    private int semester;
    private String department;
    private LocalDateTime createdAt;

    public Course() {}

    public Course(int id, String courseCode, String courseName, int teacherId,
                  int semester, String department, LocalDateTime createdAt) {
        this.id          = id;
        this.courseCode  = courseCode;
        this.courseName  = courseName;
        this.teacherId   = teacherId;
        this.semester    = semester;
        this.department  = department;
        this.createdAt   = createdAt;
    }

    // ── Getters and Setters ───────────────────────────────────────
    public int getId()                           { return id; }
    public void setId(int id)                    { this.id = id; }

    public String getCourseCode()                { return courseCode; }
    public void setCourseCode(String courseCode) { this.courseCode = courseCode; }

    public String getCourseName()                { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public int getTeacherId()                    { return teacherId; }
    public void setTeacherId(int teacherId)      { this.teacherId = teacherId; }

    public String getTeacherName()               { return teacherName; }
    public void setTeacherName(String teacherName){ this.teacherName = teacherName; }

    public int getSemester()                     { return semester; }
    public void setSemester(int semester)        { this.semester = semester; }

    public String getDepartment()                { return department; }
    public void setDepartment(String department) { this.department = department; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt){ this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "Course{" +
                "id=" + id +
                ", courseCode='" + courseCode + '\'' +
                ", courseName='" + courseName + '\'' +
                ", teacherId=" + teacherId +
                ", semester=" + semester +
                ", department='" + department + '\'' +
                '}';
    }
}
