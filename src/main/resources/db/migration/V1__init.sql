-- V1: initial schema for the PR reviewer.
-- Two tables describe *where* the bot is installed and *what* it watches.

-- A GitHub App installation (one per org/user that installed the bot).
CREATE TABLE installations (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    github_installation_id BIGINT       NOT NULL,
    account_login          VARCHAR(255),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_installations_github_installation_id UNIQUE (github_installation_id)
);

-- Repositories that a given installation grants the bot access to.
CREATE TABLE repositories (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    installation_id BIGINT       NOT NULL,
    full_name       VARCHAR(512) NOT NULL,
    default_branch  VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_repositories_full_name UNIQUE (full_name),
    CONSTRAINT fk_repositories_installation
        FOREIGN KEY (installation_id) REFERENCES installations (id) ON DELETE CASCADE
);

-- FK lookup column is not covered by the UNIQUE constraints above,
-- so add an index to keep "all repos for an installation" queries fast.
CREATE INDEX idx_repositories_installation_id ON repositories (installation_id);
