-- ================================================================
-- ScanIn: Course 21 (CS23334) Verified Rollback Script
-- Reverses Stage 9 Course 21 migration back to original state
-- 
-- CORRECTED ROLLBACK ORDER:
-- 1. Delete live attendance record 615 first (prevents duplicate PK on ID 615)
-- 2. Restore all 7 archived attendance rows (614–620) with original columns & session links
-- 3. Restore all session values (208–214) directly from archived_qr_sessions snapshots
-- ================================================================

START TRANSACTION;

-- Step 1: Delete rebound attendance record 615 from live table
DELETE FROM attendance WHERE id = 615;

-- Step 2: Restore original attendance records 614–620 from archived_attendance
INSERT INTO attendance (id, student_id, course_id, qr_session_id, status, marked_at)
SELECT original_id, student_id, course_id, qr_session_id, status, marked_at
FROM archived_attendance
WHERE original_id BETWEEN 614 AND 620
ORDER BY original_id;

-- Step 3: Restore session values from archived_qr_sessions snapshots
UPDATE qr_sessions qs
JOIN archived_qr_sessions aqs ON qs.id = aqs.original_id
SET qs.session_token = aqs.session_token,
    qs.course_id = aqs.course_id,
    qs.teacher_id = aqs.teacher_id,
    qs.is_active = aqs.is_active,
    qs.expires_at = aqs.expires_at,
    qs.created_at = aqs.created_at,
    qs.closed_at = aqs.closed_at
WHERE aqs.original_id BETWEEN 208 AND 214;

COMMIT;
