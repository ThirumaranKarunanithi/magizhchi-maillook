package software.magizhchi.maillook.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Bank alerts (SIB's) whose body starts with MIME headers, or puts HTML in the plain-text part. */
class MimeLeftoversTest {

    @Test
    void previewSkipsHeaderLinesLeftInTheBody() {
        String body = "text/html; charset=utf-8\n\nDear Customer, Rs.500.00 has been debited from your account.";
        assertThat(MessageParser.snippet(body)).isEqualTo("Dear Customer, Rs.500.00 has been debited from your account.");
        assertThat(MessageParser.snippet("Content-Type: text/html; charset=\"UTF-8\"\nContent-Transfer-Encoding: 7bit\n\nHello"))
                .isEqualTo("Hello");
        assertThat(MessageParser.snippet("Hello there\nContent-Type is a header name")).isEqualTo("Hello there Content-Type is a header name");
    }

    @Test
    void htmlInThePlainPartBecomesTextAndHtml() throws Exception {
        String raw = "From: SIB <alerts@sib.example>\r\nTo: a@b.example\r\nSubject: Debit Alert From SIB\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                + "text/html; charset=utf-8\r\n\r\n<html><body><p>Dear Customer,</p><p>Rs.500.00 has been debited.</p></body></html>\r\n";
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        MessageParser.Parsed p = MessageParser.parse(m);
        assertThat(p.html()).startsWith("<html>");
        assertThat(p.text()).contains("Dear Customer,").contains("Rs.500.00 has been debited.").doesNotContain("<p>").doesNotContain("charset");
        assertThat(MessageParser.snippet(p.text(), p.html())).isEqualTo("Dear Customer, Rs.500.00 has been debited.");
    }
}
