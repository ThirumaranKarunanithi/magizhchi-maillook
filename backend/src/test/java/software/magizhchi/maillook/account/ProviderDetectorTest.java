package software.magizhchi.maillook.account;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding IMAP/SMTP servers for any address: known providers, Thunderbird's provider list, its config format. */
class ProviderDetectorTest {

    @Test
    void knowsTheBigProvidersByTheirDomain() {
        assertThat(ProviderDetector.known("gmail.com").imapHost()).isEqualTo("imap.gmail.com");
        assertThat(ProviderDetector.known("hotmail.co.uk").provider()).isEqualTo("microsoft");
        assertThat(ProviderDetector.known("outlook.in").smtpPort()).isEqualTo(587);
        assertThat(ProviderDetector.known("yahoo.co.in").imapHost()).isEqualTo("imap.mail.yahoo.com");
        assertThat(ProviderDetector.known("icloud.com").imapHost()).isEqualTo("imap.mail.me.com");
        assertThat(ProviderDetector.known("me.com").smtpHost()).isEqualTo("smtp.mail.me.com");
        assertThat(ProviderDetector.known("aol.com").imapHost()).isEqualTo("imap.aol.com");
        assertThat(ProviderDetector.known("gmx.de").imapHost()).isEqualTo("imap.gmx.net");
        assertThat(ProviderDetector.known("yandex.ru").imapHost()).isEqualTo("imap.yandex.com");
        assertThat(ProviderDetector.known("rediffmail.com").imapHost()).isEqualTo("imap.rediffmail.com");
        assertThat(ProviderDetector.known("proton.me").provider()).isEqualTo("proton");
        assertThat(ProviderDetector.known("magizhchi.academy")).isNull();
    }

    static final String ISP_XML = """
            <?xml version="1.0"?>
            <clientConfig version="1.1">
              <emailProvider id="example-isp.test">
                <domain>example-isp.test</domain>
                <incomingServer type="pop3">
                  <hostname>pop.example-isp.test</hostname><port>995</port><socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <incomingServer type="imap">
                  <hostname>imap.example-isp.test</hostname><port>143</port><socketType>plain</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <incomingServer type="imap">
                  <hostname>mail.%EMAILDOMAIN%</hostname><port>993</port><socketType>SSL</socketType>
                  <username>%EMAILLOCALPART%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.example-isp.test</hostname><port>25</port><socketType>plain</socketType>
                </outgoingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.example-isp.test</hostname><port>587</port><socketType>STARTTLS</socketType>
                </outgoingServer>
              </emailProvider>
            </clientConfig>
            """;

    @Test
    void readsThunderbirdsConfigAndPicksSecureServers() {
        ProviderDetector.Preset p = ProviderDetector.parseConfig(ISP_XML, "example-isp.test", "asha@example-isp.test");
        assertThat(p).isNotNull();
        assertThat(p.imapHost()).isEqualTo("mail.example-isp.test");
        assertThat(p.imapPort()).isEqualTo(993);
        assertThat(p.smtpHost()).isEqualTo("smtp.example-isp.test");
        assertThat(p.smtpPort()).isEqualTo(587);
        assertThat(p.note()).contains("signs in with \"asha\"");
        // Plain-text only, or no SMTP: not usable.
        assertThat(ProviderDetector.parseConfig(ISP_XML.replace("SSL", "plain").replace("STARTTLS", "plain"), "example-isp.test", "a@example-isp.test")).isNull();
        assertThat(ProviderDetector.parseConfig("<clientConfig/>", "x.test", "a@x.test")).isNull();
        assertThat(ProviderDetector.parseConfig("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><clientConfig>&e;</clientConfig>", "x.test", "a@x.test")).isNull();
    }

    @Test
    void looksUnknownDomainsUpInThunderbirdsList() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer isp = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        isp.createContext("/", ex -> {
            asked.add(ex.getRequestURI().getPath());
            boolean known = ex.getRequestURI().getPath().endsWith("/example-isp.test");
            byte[] b = (known ? ISP_XML : "not found").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(known ? 200 : 404, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        isp.start();
        try {
            ProviderDetector d = new ProviderDetector("http://localhost:" + isp.getAddress().getPort() + "/v1.1", true);
            ProviderDetector.Preset found = d.detect("asha@example-isp.test");
            assertThat(found.imapHost()).isEqualTo("mail.example-isp.test");
            assertThat(asked).contains("/v1.1/example-isp.test");

            ProviderDetector.Preset guessed = d.detect("someone@no-such-provider.test");
            assertThat(guessed.provider()).isEqualTo("other");
            assertThat(guessed.imapHost()).isEqualTo("imap.no-such-provider.test");
            assertThat(guessed.note()).contains("a guess");
        } finally {
            isp.stop(0);
        }
    }

    @Test
    void mailHostsBecomeTheirProvidersDomain() {
        assertThat(ProviderDetector.baseDomain("10 mx1.mail.ovh.net.")).isEqualTo("ovh.net");
        assertThat(ProviderDetector.baseDomain("mx.example.co.uk")).isEqualTo("example.co.uk");
        assertThat(OAuthTokens.emailFrom("x." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"preferred_username\":\"pat@contoso.test\"}".getBytes(StandardCharsets.UTF_8)) + ".y")).isEqualTo("pat@contoso.test");
        assertThat(OAuthTokens.emailFrom("not-a-token")).isNull();
    }
}
