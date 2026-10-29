-- XEYE trainings like the activity digest (2026-10-29): a list is trained once its edits have been
-- quiet for XEYE_TRAINING_DELAY (30 min after the LAST change, not the first one) or at the nightly
-- cron if it is still pending. pending_since keeps marking "something to train"; last_change_at is
-- bumped by every change. A list pending before this migration counts from its first change.

ALTER TABLE search_lists
    ADD COLUMN IF NOT EXISTS last_change_at DATETIME(6) NULL;

UPDATE search_lists SET last_change_at = pending_since
WHERE pending_since IS NOT NULL AND last_change_at IS NULL;
