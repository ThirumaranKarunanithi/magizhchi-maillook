package software.magizhchi.maillook.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.common.Json;

import java.util.Map;

/**
 * Before the AI lane pass kept copied mail out of Needs me, it put some there (a student's leave mail to HR with
 * the owner in cc). On start, mail the AI sorted into Needs me where the owner is only in cc and isn't named
 * moves to For my info. Mail a person sorted themselves is never touched.
 */
@Component
public class CcLaneRepair {
    private static final Logger log = LoggerFactory.getLogger(CcLaneRepair.class);
    private final JdbcTemplate jdbc;

    public CcLaneRepair(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int n = repair();
            if (n > 0) log.info("Moved {} copied mails from Needs me to For my info", n);
        } catch (Exception e) {
            log.warn("Copied-mail lane repair failed: {}", e.toString());
        }
    }

    public int repair() {
        int n = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT m.id, a.email, m.to_list, m.subject, m.snippet FROM messages m "
                + "JOIN accounts a ON a.id = m.account_id WHERE m.lane = ? AND m.lane_source = 'ai' LIMIT 5000",
                LaneClassifier.NEEDS_ME)) {
            String text = (r.get("subject") == null ? "" : r.get("subject")) + "\n" + (r.get("snippet") == null ? "" : r.get("snippet"));
            String lane = LaneClassifier.keepCcOutOfNeedsMe(LaneClassifier.NEEDS_ME, (String) r.get("email"),
                    Json.strings((String) r.get("to_list")), text);
            if (!LaneClassifier.NEEDS_ME.equals(lane)) {
                n += jdbc.update("UPDATE messages SET lane = ? WHERE id = ? AND lane_source = 'ai'", lane, r.get("id"));
            }
        }
        return n;
    }
}
