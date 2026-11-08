-- Bills marked paid by a bank, card or UPI alert in the mail: which mail, how many rupees really went
-- out (for a dollar bill, the card's rupee charge), and how it was marked paid (alert, receipt, you).
ALTER TABLE bills ADD COLUMN paid_message_id BIGINT REFERENCES messages(id) ON DELETE SET NULL;
ALTER TABLE bills ADD COLUMN paid_inr NUMERIC(14, 2);
ALTER TABLE bills ADD COLUMN paid_how VARCHAR(12);
