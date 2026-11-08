-- Pay links that were mail tracking redirects (Google's c.gle and the like) go stale and were never the
-- biller's payment page: forget them, so the bill offers "Pay from the mail" or a link the person sets.
UPDATE bills SET pay_link = NULL
 WHERE pay_link LIKE 'https://c.gle/%' OR pay_link LIKE 'https://goo.gl/%' OR pay_link LIKE 'https://g.co/%';
