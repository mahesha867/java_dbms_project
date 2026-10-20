-- ============================================================
-- Migration: Stage 4 — Course Timetables & Schedules Seed
-- ============================================================

USE attendance_db;

INSERT INTO course_schedules (course_id, day_of_week, start_time, end_time, room) VALUES
(1, 'Monday',    '09:00:00', '10:30:00', 'Hall 302 - Main Campus'),
(1, 'Thursday',  '10:30:00', '12:00:00', 'Hall 302 - Main Campus'),
(2, 'Tuesday',   '11:00:00', '12:30:00', 'Room 204 - Science Block'),
(2, 'Friday',    '13:30:00', '15:00:00', 'Room 204 - Science Block'),
(3, 'Wednesday', '14:00:00', '15:30:00', 'Lab 5 - Computing Center');
