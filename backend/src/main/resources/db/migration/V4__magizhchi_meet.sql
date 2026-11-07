-- Magizhchi Meet rooms started from an email thread, so the whole thread shows its room.
CREATE TABLE meet_links (
    account_id   BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    thread_key   VARCHAR(998) NOT NULL,
    card_id      VARCHAR(64) NOT NULL,
    card_name    VARCHAR(300),
    starts_at    TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, thread_key, card_id)
);
