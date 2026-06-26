-- V3: remember the GitHub review id returned when we post a review.
-- Nullable: a review may be DONE in our DB but not (yet) posted to GitHub.
-- Needed later (H8) for dedupe/idempotency.
ALTER TABLE reviews ADD COLUMN github_review_id BIGINT;
