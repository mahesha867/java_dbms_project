-- ============================================================
-- Migration: Stage 2 — Database and OOP Foundations
-- Repeatable migration script preserving all existing data
-- ============================================================

USE attendance_db;

-- 1. Expand attendance status ENUM to include 'OD' (On Duty) and 'EXCUSED'
ALTER TABLE attendance 
    MODIFY COLUMN status ENUM('PRESENT','ABSENT','LATE','OD','EXCUSED') DEFAULT 'PRESENT';

-- 2. Create attendance_audit_log table (append-only audit history)
CREATE TABLE IF NOT EXISTS attendance_audit_log (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    attendance_id      INT NULL,
    student_id         INT NOT NULL,
    session_id         INT NULL,
    changed_by_user_id INT NOT NULL,
    old_status         VARCHAR(20),
    new_status         VARCHAR(20) NOT NULL,
    reason             TEXT NOT NULL,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (student_id) REFERENCES students(student_id) ON DELETE CASCADE,
    FOREIGN KEY (changed_by_user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_audit_student (student_id),
    INDEX idx_audit_session (session_id)
);

-- 3. Create scan_attempts table (stores token SHA-256 hash, no raw reusable secrets, no unnecessary IP data)
CREATE TABLE IF NOT EXISTS scan_attempts (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    session_id         INT NULL,
    student_id         INT NULL,
    token_hash         VARCHAR(64) NOT NULL,
    outcome            ENUM('SUCCESS','EXPIRED','INVALID','DUPLICATE','WRONG_COURSE') NOT NULL,
    attempted_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scan_token_hash (token_hash),
    INDEX idx_scan_student_session (student_id, session_id)
);

-- 4. Create course_schedules table (course semester timetable / planned sessions)
CREATE TABLE IF NOT EXISTS course_schedules (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    course_id          INT NOT NULL,
    day_of_week        VARCHAR(15) NOT NULL,
    start_time         TIME NOT NULL,
    end_time           TIME NOT NULL,
    room               VARCHAR(50),
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
);

-- 5. Update demo user credentials to legitimate SHA-256 hashes (removes dependence on hardcoded plaintext bypasses)
-- teacher123 hash: cde383eee8ee7a4400adf7a15f716f179a2eb97646b37e089eb8d6d04e663416
UPDATE users 
SET password = 'cde383eee8ee7a4400adf7a15f716f179a2eb97646b37e089eb8d6d04e663416' 
WHERE role = 'TEACHER';

-- student123 hash: 703b0a3d6ad75b649a28adde7d83c6251da457549263bc7ff45ec709b0a8448b
UPDATE users 
SET password = '703b0a3d6ad75b649a28adde7d83c6251da457549263bc7ff45ec709b0a8448b' 
WHERE role = 'STUDENT';
