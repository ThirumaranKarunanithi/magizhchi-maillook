-- Royal signatures can carry the person's photo and the company's logo. Both are kept (small) beside the
-- banners, so the designer reopens with them; only old banners are ever let go, never the photo or
-- logo the current design uses.
ALTER TABLE signature_images ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'banner';
