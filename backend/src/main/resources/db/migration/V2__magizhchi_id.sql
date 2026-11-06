-- People now sign in with Magizhchi ID; this links a Mail user to their suite account.
ALTER TABLE users ADD COLUMN magizhchi_account_id VARCHAR(64);
CREATE UNIQUE INDEX ux_users_magizhchi_account ON users (magizhchi_account_id);
