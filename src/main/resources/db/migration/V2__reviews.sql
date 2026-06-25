-- V2: persisted review results.
-- A "review" is one analysis run of a PR at a specific commit; its findings are
-- stored as "review_comments".

CREATE TABLE reviews (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id BIGINT       NOT NULL,
    pr_number     INTEGER      NOT NULL,
    commit_sha    VARCHAR(64)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    summary       TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    CONSTRAINT fk_reviews_repository
        FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE
);

-- Lookups: all reviews for a repo, and all reviews for a given PR.
-- NOTE: intentionally NO unique on (repository_id, pr_number, commit_sha) — the
-- same commit may be re-reviewed; dedupe is a later step.
CREATE INDEX idx_reviews_repository_id ON reviews (repository_id);
CREATE INDEX idx_reviews_repo_pr ON reviews (repository_id, pr_number);

CREATE TABLE review_comments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id   BIGINT        NOT NULL,
    file_path   VARCHAR(1024) NOT NULL,
    line_number INTEGER,
    severity    VARCHAR(20)   NOT NULL,
    title       VARCHAR(512),
    body        TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_review_comments_review
        FOREIGN KEY (review_id) REFERENCES reviews (id) ON DELETE CASCADE
);

CREATE INDEX idx_review_comments_review_id ON review_comments (review_id);
