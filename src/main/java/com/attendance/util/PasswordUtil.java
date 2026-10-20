package com.attendance.util;

import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Enterprise Password and Security Utility.
 * Demonstrates:
 * - High-workfactor BCrypt password hashing using org.mindrot.jbcrypt
 * - Safe migration path: recognizes legacy SHA-256 hashes and upgrades on login
 * - Safe token hashing (SHA-256) for audit trails and non-reusable scan attempt logging
 */
public class PasswordUtil {

    private static final int BCRYPT_LOG_ROUNDS = 10;

    /**
     * Hashes a plain-text password using standard BCrypt with a secure random salt.
     */
    public static String hashPassword(String plainPassword) {
        if (plainPassword == null) return null;
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
    }

    /**
     * Verifies plain text password against stored hash.
     * Supports:
     * 1. Modern BCrypt ($2a$, $2b$, $2y$ prefix).
     * 2. Safe migration path: Legacy SHA-256 (64-character hex strings) with constant-time check.
     */
    public static boolean verifyPassword(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) {
            return false;
        }

        // 1. BCrypt verification
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            try {
                return BCrypt.checkpw(plainPassword, storedHash);
            } catch (Exception e) {
                return false;
            }
        }

        // 2. Legacy SHA-256 migration check
        if (storedHash.length() == 64) {
            String sha256Hex = hashTokenSha256(plainPassword);
            return MessageDigest.isEqual(
                sha256Hex.getBytes(StandardCharsets.UTF_8),
                storedHash.toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        }

        return false;
    }

    /**
     * Checks if a stored password hash is using an older algorithm and requires an upgrade to BCrypt.
     */
    public static boolean needsUpgrade(String storedHash) {
        if (storedHash == null) return true;
        return !storedHash.startsWith("$2a$");
    }

    /**
     * Generates a safe SHA-256 hash for audit identifiers (e.g. scan attempt token hashes).
     * Non-password use only: never stores reusable secrets.
     */
    public static String hashTokenSha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
