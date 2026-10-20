-- ================================================================
-- ScanIn: Course 21 (CS23334) Archive-First Reversible Migration
-- Canonical Meeting: Session 208
-- Canonical Attendance: ID 615 (PRESENT) → rebind to Session 208
-- Phantom Absent Records: 614, 616–620 → archived & removed from live table
-- Phantom Sessions: 209–214 → marked MERGED_INTO_208:
-- ================================================================

START TRANSACTION;

-- Step 1: Ensure archive tables exist
CREATE TABLE IF NOT EXISTS archived_qr_sessions (
    archive_id          INT AUTO_INCREMENT PRIMARY KEY,
    archived_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    archive_reason      VARCHAR(255) NOT NULL,
    merged_into_session_id INT DEFAULT NULL,
    original_id         INT NOT NULL,
    session_token       VARCHAR(255) NOT NULL,
    course_id           INT NOT NULL,
    teacher_id          INT NOT NULL,
    is_active           TINYINT(1),
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP,
    closed_at           TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS archived_attendance (
    archive_id          INT AUTO_INCREMENT PRIMARY KEY,
    archived_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    archive_reason      VARCHAR(255) NOT NULL,
    canonical_session_id INT DEFAULT NULL,
    original_id         INT NOT NULL,
    student_id          INT NOT NULL,
    course_id           INT NOT NULL,
    qr_session_id       INT NOT NULL,
    status              ENUM('PRESENT','ABSENT','LATE','OD','EXCUSED') NOT NULL,
    marked_at           TIMESTAMP
);

-- Step 2: Ensure archive has rows (if not already archived)
INSERT INTO archived_qr_sessions
    (archive_reason, merged_into_session_id, original_id, session_token,
     course_id, teacher_id, is_active, expires_at, created_at, closed_at)
SELECT
    CASE
        WHEN id = 208 THEN 'course21_migration: canonical meeting session preserved'
        ELSE 'course21_migration: phantom session merged_into=208'
    END,
    CASE WHEN id = 208 THEN NULL ELSE 208 END,
    id, session_token, course_id, teacher_id, is_active, expires_at, created_at, closed_at
FROM qr_sessions
WHERE id BETWEEN 208 AND 214
  AND id NOT IN (SELECT original_id FROM (SELECT original_id FROM archived_qr_sessions) as existing_qs);

INSERT INTO archived_attendance
    (archive_reason, canonical_session_id, original_id, student_id,
     course_id, qr_session_id, status, marked_at)
SELECT
    CASE
        WHEN id = 615 THEN 'course21_migration: canonical PRESENT record rebind to session 208'
        ELSE CONCAT('course21_migration: phantom ABSENT record archived (was session=', qr_session_id, ')')
    END,
    208,
    id, student_id, course_id, qr_session_id, status, marked_at
FROM attendance
WHERE id BETWEEN 614 AND 620
  AND id NOT IN (SELECT original_id FROM (SELECT original_id FROM archived_attendance) as existing_att);

-- Step 3: Apply active table modifications
-- Delete phantom ABSENT 614 first so 615 can be rebound to session 208 without unique key violation
DELETE FROM attendance WHERE id = 614;

-- Rebind 615 to canonical session 208
UPDATE attendance SET qr_session_id = 208 WHERE id = 615;

-- Delete remaining phantom ABSENT rows 616–620
DELETE FROM attendance WHERE id IN (616, 617, 618, 619, 620);

-- Mark sessions 209–214 as merged into 208
UPDATE qr_sessions
SET session_token = CONCAT('MERGED_INTO_208:', session_token)
WHERE id BETWEEN 209 AND 214
  AND session_token NOT LIKE 'MERGED_INTO_%';

COMMIT;
