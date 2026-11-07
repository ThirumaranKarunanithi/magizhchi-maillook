-- Bills: the account's name as the bill shows it ("alwarpuram"), and a UPI ID to pay it to.
ALTER TABLE bills ADD COLUMN account_name VARCHAR(100);
ALTER TABLE bills ADD COLUMN upi_id VARCHAR(100);
