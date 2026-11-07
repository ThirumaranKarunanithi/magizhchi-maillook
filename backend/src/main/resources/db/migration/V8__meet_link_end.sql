-- When a booked Meet room ends, so an invite sent from the person's own mailbox can carry the full time.
ALTER TABLE meet_links ADD COLUMN ends_at TIMESTAMP WITH TIME ZONE;
