-- How a mailbox signs in: 'password' (IMAP/SMTP password or App Password, kept encrypted in
-- password_enc) or 'oauth' (Sign in with Google / Microsoft: password_enc then holds the
-- encrypted refresh token and IMAP/SMTP use XOAUTH2 with a short-lived access token).
ALTER TABLE accounts ADD COLUMN auth VARCHAR(16) NOT NULL DEFAULT 'password';
