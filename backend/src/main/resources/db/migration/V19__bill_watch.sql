-- Regular bills: which "this bill hasn't come" alerts were sent (one per regular bill per expected date).
CREATE TABLE bill_watch_sent (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    watch_key  VARCHAR(160) NOT NULL,
    period     VARCHAR(10) NOT NULL,
    sent_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, watch_key, period)
);
