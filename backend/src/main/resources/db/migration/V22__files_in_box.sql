-- Where a person's attachments live: 'box' (every attachment is saved to their own Magizhchi Box by
-- itself, and the Files page works from Box) or 'local' (attachments stay in the mail and download to
-- the device).
ALTER TABLE users ADD COLUMN files_store VARCHAR(8) NOT NULL DEFAULT 'box';
-- The automatic save's last trouble, shown in Settings: full | no_id | unreachable (NULL when fine).
ALTER TABLE users ADD COLUMN box_auto_state VARCHAR(16);
ALTER TABLE users ADD COLUMN box_auto_note VARCHAR(400);
ALTER TABLE users ADD COLUMN box_auto_paused_until TIMESTAMP WITH TIME ZONE;

-- Mails whose attachments the automatic save has dealt with (saved, skipped or nothing to save).
CREATE TABLE box_auto_done (
    message_id  BIGINT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    done_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Attachments the automatic save left out, and why (too_big, failed), so they aren't tried again and again.
CREATE TABLE box_skips (
    message_id  BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    att_index   INT NOT NULL,
    reason      VARCHAR(40) NOT NULL,
    at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, att_index)
);
