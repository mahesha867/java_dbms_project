-- ============================================================
-- Migration: Stage 3 — BCrypt Password Hash Upgrade
-- Upgrades all demo users to standard BCrypt hashes
-- ============================================================

USE attendance_db;

-- 1. Upgrade TEACHER demo accounts to BCrypt (password: teacher123)
UPDATE users 
SET password = '$2a$10$4N8R1PtcJq..fGDPqmk4Gee8JbVoTrDMxSc1iZs74WU0BemBIU2MO' 
WHERE role = 'TEACHER';

-- 2. Upgrade STUDENT demo accounts to BCrypt (password: student123)
UPDATE users 
SET password = '$2a$10$wALPiFbd0xdBlEilGrJxYuk7s0pWTI7WZAhg7uJI8XSCl7T9iltYC' 
WHERE role = 'STUDENT';
