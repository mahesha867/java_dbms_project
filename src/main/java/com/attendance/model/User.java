package com.attendance.model;

import java.time.LocalDateTime;

/**
 * Abstract base class representing a User in the system.
 * Demonstrates: Abstraction, Encapsulation
 * Both Teacher and Student inherit from this class (Inheritance).
 */
public abstract class User {

    // Encapsulated fields
    private int id;
    private String name;
    private String email;
    private String password;
    private String role;
    private String phone;
    private LocalDateTime createdAt;

    // ── Constructors ──────────────────────────────────────────────
    public User() {}

    public User(int id, String name, String email, String password,
                String role, String phone, LocalDateTime createdAt) {
        this.id        = id;
        this.name      = name;
        this.email     = email;
        this.password  = password;
        this.role      = role;
        this.phone     = phone;
        this.createdAt = createdAt;
    }

    // ── Abstract method – Polymorphism ────────────────────────────
    /**
     * Returns a display-friendly dashboard URL for this user type.
     * Each subclass overrides this (Polymorphism).
     */
    public abstract String getDashboardPath();

    /**
     * Returns role-specific information string.
     * Polymorphic behaviour.
     */
    public abstract String getRoleInfo();

    // ── Getters & Setters (Encapsulation) ────────────────────────
    public int getId()                          { return id; }
    public void setId(int id)                   { this.id = id; }

    public String getName()                     { return name; }
    public void setName(String name)            { this.name = name; }

    public String getEmail()                    { return email; }
    public void setEmail(String email)          { this.email = email; }

    public String getPassword()                 { return password; }
    public void setPassword(String password)    { this.password = password; }

    public String getRole()                     { return role; }
    public void setRole(String role)            { this.role = role; }

    public String getPhone()                    { return phone; }
    public void setPhone(String phone)          { this.phone = phone; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt){ this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "', email='" + email
                + "', role='" + role + "'}";
    }
}
