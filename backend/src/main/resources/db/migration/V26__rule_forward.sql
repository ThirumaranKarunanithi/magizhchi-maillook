-- An automation can also forward the mail it catches ("job applications go to the manager and HR").
-- forward_to: the addresses, comma-separated. rule_forwards: each mail forwarded once, with the outcome.

ALTER TABLE mail_rules ADD COLUMN forward_to VARCHAR(1000);

CREATE TABLE rule_forwards (
    message_id    BIGINT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    rule_id       BIGINT,
    forwarded_to  VARCHAR(1000) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'queued',
    error         VARCHAR(400),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
