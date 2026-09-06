package com.moneycompass.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    /** BCrypt hash. Never the plaintext, and never returned by any API. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 16)
    private ProfileType profileType;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    public User(UUID id, String email, String passwordHash, ProfileType profileType) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.profileType = profileType;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public ProfileType getProfileType() { return profileType; }
    public Instant getCreatedAt() { return createdAt; }

    public void setProfileType(ProfileType profileType) { this.profileType = profileType; }
}
