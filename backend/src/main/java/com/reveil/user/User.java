package com.reveil.user;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Utilisateur de Reveil. L'e-mail est stocké normalisé (minuscules) et unique. */
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true, name = "uk_users_email")
    private String email;

    /** Hash bcrypt du mot de passe — jamais le mot de passe en clair. */
    private String passwordHash;

    @CreatedDate
    private Instant createdAt;

    protected User() {
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
