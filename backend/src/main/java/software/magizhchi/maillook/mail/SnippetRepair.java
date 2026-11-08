package software.magizhchi.maillook.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mail synced before MessageParser learned to drop MIME leftovers shows "text/html; charset=utf-8" or
 * raw HTML as its preview (SIB's alerts did). On start, those previews (and their text) are made again
 * from the stored body. Cheap: only rows whose preview looks wrong are touched.
 */
@Component
public class SnippetRepair {
    private static final Logger log = LoggerFactory.getLogger(SnippetRepair.class);
    private final JdbcTemplate jdbc;

    public SnippetRepair(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int n = repair();
            if (n > 0) log.info("Remade {} mail previews", n);
        } catch (Exception e) {
            log.warn("Mail preview repair failed: {}", e.toString());
        }
    }

    public int repair() {
        int n = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, body_text, body_html FROM messages WHERE " +
                "LOWER(snippet) LIKE 'text/html%' OR LOWER(snippet) LIKE 'text/plain%' OR LOWER(snippet) LIKE 'content-type%' " +
                "OR snippet LIKE '<%' OR snippet LIKE '--%' LIMIT 5000")) {
            String text = (String) r.get("body_text");
            String html = (String) r.get("body_html");
            String cleanText = MessageParser.stripMimeLeftovers(text);
            if (cleanText != null && MessageParser.looksLikeHtml(cleanText)) {
                if (html == null) html = cleanText;
                cleanText = MessageParser.htmlToText(cleanText);
            }
            String snippet = MessageParser.snippet(cleanText, html);
            jdbc.update("UPDATE messages SET snippet = ?, body_text = ? WHERE id = ?",
                    snippet.length() > 400 ? snippet.substring(0, 400) : snippet, cleanText, r.get("id"));
            n++;
        }
        return n;
    }
}
