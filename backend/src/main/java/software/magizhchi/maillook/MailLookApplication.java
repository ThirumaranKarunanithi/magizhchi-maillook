package software.magizhchi.maillook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Magizhchi MailLook — the mail app that makes you happy again. */
@SpringBootApplication(exclude = MailSenderAutoConfiguration.class)
@EnableScheduling
public class MailLookApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailLookApplication.class, args);
    }
}
