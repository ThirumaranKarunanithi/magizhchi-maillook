-- Bills: the kind of spending the person chose (internet, power, software …); empty means MailLook guesses
-- it from the biller's name.
ALTER TABLE bills ADD COLUMN category VARCHAR(20);
