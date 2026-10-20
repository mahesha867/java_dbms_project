package com.attendance.model;

import java.time.LocalDateTime;

/**
 * Entity representing an attendance scan attempt.
 * Security Note:
 * Stores only a cryptographic SHA-256 hash of the token (never the raw reusable token).
 * IP and device data are omitted to respect student privacy and data minimization principles.
 */
public class ScanAttempt {

    private int id;
    private Integer sessionId;
    private Integer studentId;
    private String tokenHash;
    private String outcome; // SUCCESS, EXPIRED, INVALID, DUPLICATE, WRONG_COURSE
    private LocalDateTime attemptedAt;

    public ScanAttempt() {}

    public ScanAttempt(Integer sessionId, Integer studentId, String tokenHash, String outcome) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.tokenHash = tokenHash;
        this.outcome = outcome;
        this.attemptedAt = LocalDateTime.now();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public Integer getSessionId() { return sessionId; }
    public void setSessionId(Integer sessionId) { this.sessionId = sessionId; }

    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public LocalDateTime getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(LocalDateTime attemptedAt) { this.attemptedAt = attemptedAt; }
}
