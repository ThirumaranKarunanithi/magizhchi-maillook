-- A designed (HTML) signature per mailbox: which template and the details typed into it, as JSON, so
-- the designer reopens where they left it. NULL means the plain-text signature is used on its own.
ALTER TABLE accounts ADD COLUMN signature_design TEXT;

-- The signature's banner picture (crest, name and title, drawn in the browser at 3x size; JPEG or PNG). Mail
-- loads it from MailLook by its token. Each save gets a new token; older pictures stay, because mail
-- already sent still shows them (only the oldest beyond the last 20 per mailbox are let go).
CREATE TABLE signature_images (
    token         VARCHAR(40) PRIMARY KEY,
    account_id    BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    width         INT NOT NULL,
    height        INT NOT NULL,
    content_type  VARCHAR(40) NOT NULL,
    data          BYTEA NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_signature_images_account ON signature_images (account_id);
