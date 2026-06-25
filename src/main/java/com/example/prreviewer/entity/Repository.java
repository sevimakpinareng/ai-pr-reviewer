package com.example.prreviewer.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A repository the bot watches, owned by an {@link Installation}. */
@Entity
@Table(name = "repositories")
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installation_id", nullable = false)
    private Installation installation;

    @Column(name = "full_name", nullable = false, unique = true)
    private String fullName;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Repository() {
        // for JPA
    }

    public Repository(Installation installation, String fullName, String defaultBranch) {
        this.installation = installation;
        this.fullName = fullName;
        this.defaultBranch = defaultBranch;
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

    public Installation getInstallation() {
        return installation;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
