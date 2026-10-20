-- ============================================================
-- Migration: Stage 6 — Live QR Attendance Flow & Security
-- Repeatable migration script preserving all existing data
-- ============================================================

USE attendance_db;

-- 1. Expand session_token in qr_sessions to VARCHAR(255) to support cryptographically signed HMAC tokens
ALTER TABLE qr_sessions 
    MODIFY COLUMN session_token VARCHAR(255) NOT NULL;

-- 2. Expand scan_attempts outcome ENUM to include 'REPLAYED' and 'CLOSED_SESSION'
ALTER TABLE scan_attempts 
    MODIFY COLUMN outcome ENUM('SUCCESS','EXPIRED','INVALID','DUPLICATE','WRONG_COURSE','REPLAYED','CLOSED_SESSION') NOT NULL;

-- 3. Add closed_at timestamp column to qr_sessions if not exists
SET @dbname = DATABASE();
SET @tablename = "qr_sessions";
SET @columnname = "closed_at";
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  "SELECT 1",
  "ALTER TABLE qr_sessions ADD COLUMN closed_at TIMESTAMP NULL DEFAULT NULL AFTER expires_at;"
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
