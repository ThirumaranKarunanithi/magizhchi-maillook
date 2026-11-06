-- Magizhchi Box: each person's own drive, connected automatically the first time they save an attachment.
ALTER TABLE users ADD COLUMN box_api_key_enc TEXT;
ALTER TABLE users ADD COLUMN box_drive_id BIGINT;
ALTER TABLE users ADD COLUMN box_connected_at TIMESTAMP WITH TIME ZONE;

-- Attachments already saved to Box, so the app can say "In Box" and never upload the same file twice.
CREATE TABLE box_saves (
    message_id   BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    att_index    INT NOT NULL,
    box_file_id  BIGINT NOT NULL,
    folder_path  VARCHAR(500) NOT NULL,
    saved_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, att_index)
);
