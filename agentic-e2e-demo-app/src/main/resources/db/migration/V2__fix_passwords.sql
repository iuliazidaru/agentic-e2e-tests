-- Fix BCrypt password hashes (admin123 and test123)
UPDATE users SET password = '$2a$10$6rNvazqftYnozEOxAC9qiO1q1BZNb.E6MU.EwhdF5RzYYCsE39EYS' WHERE username = 'admin';
UPDATE users SET password = '$2a$10$fC7Jed.IR4xiJEHaP5mlVu8ijf6nVmtILI/xSgzsrU/4dg0emGeOG' WHERE username = 'testuser';
