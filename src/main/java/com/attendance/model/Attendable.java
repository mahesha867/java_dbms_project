package com.attendance.model;

/**
 * Interface demonstrating OOP Interface concept.
 * Defines contract for any entity that participates in attendance verification.
 */
public interface Attendable {
    boolean canMarkAttendance(String sessionToken);
    String getIdentifier();
}
