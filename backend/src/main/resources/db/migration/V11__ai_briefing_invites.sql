-- A calendar invite sent inside the mail itself (text/calendar), kept so the invite card can show it
-- without a trip to the mail server. Invites sent as an .ics file are read from the attachment.
ALTER TABLE messages ADD COLUMN calendar_ics TEXT;

-- The morning briefing alert: the person's time zone, the hour it comes (null = off) and the day it last came.
ALTER TABLE users ADD COLUMN tz VARCHAR(64);
ALTER TABLE users ADD COLUMN briefing_hour INT DEFAULT 8;
ALTER TABLE users ADD COLUMN briefing_sent_on DATE;
