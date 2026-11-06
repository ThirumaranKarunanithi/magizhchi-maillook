package software.magizhchi.maillook.mail;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pulls mail from each mailbox over IMAP into the local store: INBOX (newest N on first run, then
 * only new UIDs) and the Sent folder (for threads and tone learning). Runs on a timer and on demand.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int FLAG_WINDOW = 300;

    private final AccountRepo accounts;
    private final MessageRepo messages;
    private final ImapClient imap;
    private final AiService ai;
    private final int initialInbox;
    private final int initialSent;
    private final ExecutorService pool;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public SyncService(AccountRepo accounts, MessageRepo messages, ImapClient imap, AiService ai,
                       @Value("${maillook.sync.initial-inbox}") int initialInbox,
                       @Value("${maillook.sync.initial-sent}") int initialSent,
                       @Value("${maillook.sync.threads}") int threads) {
        this.accounts = accounts;
        this.messages = messages;
        this.imap = imap;
        this.ai = ai;
        this.initialInbox = initialInbox;
        this.initialSent = initialSent;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads));
    }

    @Scheduled(initialDelay = 15_000, fixedDelayString = "${maillook.sync.interval-ms}")
    public void syncAllOnTimer() {
        for (Account a : accounts.all()) request(a.id());
    }

    public void requestForUser(long userId) {
        for (Account a : accounts.forUser(userId)) request(a.id());
    }

    /** Queues a sync for one account unless one is already running for it. */
    public void request(long accountId) {
        if (!running.add(accountId)) return;
        pool.submit(() -> {
            try {
                syncNow(accountId);
            } finally {
                running.remove(accountId);
            }
        });
    }

    private void syncNow(long accountId) {
        Account a = accounts.find(accountId).orElse(null);
        if (a == null) return;
        accounts.setStatus(a.id(), "syncing", a.lastError());
        Store store = null;
        try {
            store = imap.connect(a);
            Set<String> own = new HashSet<>();
            for (Account mine : accounts.forUser(a.userId())) own.add(mine.email().toLowerCase(Locale.ROOT));
            Map<String, String> rules = messages.senderRules(a.userId());

            List<Long> newInbox = syncFolder(store, a, (IMAPFolder) store.getFolder("INBOX"), "INBOX", own, rules);
            IMAPFolder sent = imap.folder(store, a, ImapClient.Role.SENT, false);
            if (sent != null) {
                // Re-read the account: the INBOX pass moved its cursor.
                syncFolder(store, accounts.find(a.id()).orElse(a), sent, "SENT", own, rules);
            }
            accounts.syncDone(a.id());
            if (!newInbox.isEmpty()) ai.classifyLater(a.userId(), newInbox);
        } catch (ApiException e) {
            accounts.setStatus(a.id(), "error", e.getMessage());
        } catch (Exception e) {
            String msg = imap.friendly(e, a.imapHost()).getMessage();
            log.warn("Sync failed for {}: {}", a.email(), e.toString());
            accounts.setStatus(a.id(), "error", msg.length() > 900 ? msg.substring(0, 900) : msg);
        } finally {
            ImapClient.closeQuietly(store);
        }
    }

    private List<Long> syncFolder(Store store, Account a, IMAPFolder folder, String code,
                                  Set<String> own, Map<String, String> rules) throws Exception {
        List<Long> inserted = new ArrayList<>();
        folder.open(Folder.READ_ONLY);
        try {
            boolean inbox = "INBOX".equals(code);
            long uidValidity = folder.getUIDValidity();
            Long knownValidity = inbox ? a.inboxUidValidity() : a.sentUidValidity();
            Long lastUid = inbox ? a.inboxLastUid() : a.sentLastUid();
            if (knownValidity == null || knownValidity != uidValidity) {
                if (knownValidity != null) messages.deleteFolder(a.id(), code);
                lastUid = null;
            }

            int count = folder.getMessageCount();
            Message[] batch;
            if (count == 0) {
                batch = new Message[0];
            } else if (lastUid == null) {
                int n = inbox ? initialInbox : initialSent;
                batch = folder.getMessages(Math.max(1, count - n + 1), count);
            } else {
                batch = folder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
            }

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(UIDFolder.FetchProfileItem.UID);
            for (String h : new String[]{"Message-ID", "In-Reply-To", "References", "List-Unsubscribe",
                    "List-Unsubscribe-Post", "List-Id", "Precedence", "Auto-Submitted"}) {
                fp.add(h);
            }
            folder.fetch(batch, fp);

            long maxUid = lastUid == null ? 0 : lastUid;
            for (Message m : batch) {
                long uid = folder.getUID(m);
                if (lastUid != null && uid <= lastUid) continue; // "n:*" returns the last message when nothing is new
                try {
                    Long id = messages.insert(toRow(a, m, uid, code, own, rules));
                    if (id != null && inbox) inserted.add(id);
                } catch (Exception e) {
                    log.warn("Skipping message uid {} in {} of {}: {}", uid, code, a.email(), e.toString());
                }
                maxUid = Math.max(maxUid, uid);
            }
            if (inbox) accounts.setInboxCursor(a.id(), uidValidity, maxUid == 0 ? null : maxUid);
            else accounts.setSentCursor(a.id(), uidValidity, maxUid == 0 ? null : maxUid);

            if (inbox && count > 0) reconcileFlags(a, folder, count);
        } finally {
            ImapClient.closeQuietly(folder, false);
        }
        return inserted;
    }

    /** Pick up read/flag changes and removals made in other mail apps, for the newest messages. */
    private void reconcileFlags(Account a, IMAPFolder folder, int count) throws MessagingException {
        Message[] window = folder.getMessages(Math.max(1, count - FLAG_WINDOW + 1), count);
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(UIDFolder.FetchProfileItem.UID);
        folder.fetch(window, fp);
        Map<Long, boolean[]> flags = new HashMap<>();
        long minUid = Long.MAX_VALUE;
        for (Message m : window) {
            long uid = folder.getUID(m);
            minUid = Math.min(minUid, uid);
            flags.put(uid, new boolean[]{m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.FLAGGED)});
        }
        if (!flags.isEmpty()) messages.reconcile(a.id(), "INBOX", minUid, flags);
    }

    MessageRepo.NewMessage toRow(Account a, Message m, Long uid, String code,
                                 Set<String> own, Map<String, String> rules) throws Exception {
        String fromName = null;
        String fromEmail = null;
        if (m.getFrom() != null && m.getFrom().length > 0 && m.getFrom()[0] instanceof InternetAddress ia) {
            fromEmail = ia.getAddress() == null ? null : ia.getAddress().toLowerCase(Locale.ROOT);
            fromName = ia.getPersonal();
        }
        List<String> to = MessageParser.addresses(m.getRecipients(Message.RecipientType.TO));
        List<String> cc = MessageParser.addresses(m.getRecipients(Message.RecipientType.CC));

        String messageId = trimId(MessageParser.header(m, "Message-ID"));
        String inReplyTo = trimId(MessageParser.header(m, "In-Reply-To"));
        String references = MessageParser.header(m, "References");
        String subject = m.getSubject();
        String threadKey = threadKey(messageId, inReplyTo, references, subject);

        MessageParser.Parsed body = MessageParser.parse(m);

        Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        OffsetDateTime at = OffsetDateTime.ofInstant(d != null ? d.toInstant() : Instant.now(), ZoneOffset.UTC);

        String unsub = MessageParser.header(m, "List-Unsubscribe");
        String unsubPost = MessageParser.header(m, "List-Unsubscribe-Post");

        String lane = null;
        String laneSource = null;
        if ("INBOX".equals(code)) {
            String rule = fromEmail == null ? null : rules.get(fromEmail);
            if (rule != null) {
                lane = rule;
                laneSource = "rule";
            } else {
                Set<String> toEmails = new HashSet<>();
                for (String t : to) toEmails.add(MessageParser.emailOnly(t));
                lane = LaneClassifier.classify(fromEmail, toEmails, to.size(), a.email(), own, unsub,
                        MessageParser.header(m, "List-Id"), MessageParser.header(m, "Precedence"),
                        MessageParser.header(m, "Auto-Submitted"));
                laneSource = "heuristic";
            }
        }

        return new MessageRepo.NewMessage(a.id(), code, uid, messageId, threadKey, fromName, fromEmail, to, cc,
                subject, MessageParser.snippet(body.text()), body.text(), body.html(), body.attachments(), at,
                m.isSet(Flags.Flag.SEEN) || "SENT".equals(code), m.isSet(Flags.Flag.FLAGGED), lane, laneSource,
                unsub, unsubPost != null && unsubPost.toLowerCase(Locale.ROOT).contains("one-click"));
    }

    /** The thread's root Message-ID: first of References, else In-Reply-To, else this message's own. */
    public static String threadKey(String messageId, String inReplyTo, String references, String subject) {
        if (references != null) {
            String first = references.trim().split("\\s+")[0];
            String id = trimId(first);
            if (id != null && !id.isEmpty()) return id;
        }
        if (inReplyTo != null && !inReplyTo.isEmpty()) return inReplyTo;
        if (messageId != null && !messageId.isEmpty()) return messageId;
        String s = subject == null ? "" : subject.replaceAll("(?i)^((re|fwd?|aw|sv)\\s*:\\s*)+", "").trim().toLowerCase(Locale.ROOT);
        return "subject:" + s;
    }

    static String trimId(String id) {
        if (id == null) return null;
        String t = id.trim();
        int lt = t.indexOf('<');
        int gt = t.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) t = t.substring(lt, gt + 1);
        return t;
    }
}
