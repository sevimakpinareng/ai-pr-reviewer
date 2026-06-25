package com.example.prreviewer.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A GitHub App installation (the org/user that installed the bot). */
@Entity
@Table(name = "installations")
public class Installation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_installation_id", nullable = false, unique = true)
    private Long githubInstallationId;

    @Column(name = "account_login")
    private String accountLogin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Installation() {
        // for JPA
    }

    public Installation(Long githubInstallationId, String accountLogin) {
        this.githubInstallationId = githubInstallationId;
        this.accountLogin = accountLogin;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getGithubInstallationId() {
        return githubInstallationId;
    }

    public String getAccountLogin() {
        return accountLogin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
