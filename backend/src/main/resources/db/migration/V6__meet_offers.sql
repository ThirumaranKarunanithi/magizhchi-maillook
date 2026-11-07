-- Meeting times offered in a thread ("do any of these work?"), so when the other person answers
-- with one of them MailLook can book that exact time in one tap.
CREATE TABLE meet_offers (
    account_id   BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    thread_key   VARCHAR(998) NOT NULL,
    slots        TEXT NOT NULL,
    minutes      INT NOT NULL,
    topic        VARCHAR(300),
    offered_on   BIGINT NOT NULL,          -- the mail the offer answered; later mail in the thread may be the pick
    offered_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, thread_key)
);
CREATE INDEX ix_meet_links_starts ON meet_links (starts_at);
