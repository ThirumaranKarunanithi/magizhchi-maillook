package software.magizhchi.maillook.mail;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.common.ApiException;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Things you do to mail: read/flag, Done (archive), trash, attachments, send, unsubscribe. */
@Service
public class MailActions {

    private final AccountRepo accounts;
    private final MessageRepo messages;
    private final ImapClient imap;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public MailActions(AccountRepo accounts, MessageRepo messages, ImapClient imap) {
        this.accounts = accounts;
        this.messages = messages;
        this.imap = imap;
    }

    public MessageRepo.Row owned(long userId, long messageId) {
        return messages.find(userId, messageId).orElseThrow(() -> ApiException.notFound("Message"));
    }

    private Account account(MessageRepo.Row r) {
        return accounts.find(r.accountId()).orElseThrow(() -> ApiException.notFound("Account"));
    }

    /** The IMAP folder a stored message lives in. */
    private IMAPFolder serverFolder(Store store, Account a, String code) throws MessagingException {
        return switch (code) {
            case "INBOX" -> (IMAPFolder) store.getFolder("INBOX");
            case "SENT" -> imap.folder(store, a, ImapClient.Role.SENT, false);
            case "ARCHIVE" -> imap.folder(store, a, ImapClient.Role.ARCHIVE, false);
            case "TRASH" -> imap.folder(store, a, ImapClient.Role.TRASH, false);
            default -> null;
        };
    }

    // ── flags ─────────────────────────────────────────────────────

    public void setSeen(long userId, long id, boolean seen) {
        MessageRepo.Row r = owned(userId, id);
        messages.setSeen(id, seen);
        setServerFlag(r, Flags.Flag.SEEN, seen);
    }

    public void setFlagged(long userId, long id, boolean flagged) {
        MessageRepo.Row r = owned(userId, id);
        messages.setFlagged(id, flagged);
        setServerFlag(r, Flags.Flag.FLAGGED, flagged);
    }

    private void setServerFlag(MessageRepo.Row r, Flags.Flag flag, boolean on) {
        if (r.uid() == null) return;
        Account a = account(r);
        Store store = null;
        IMAPFolder f = null;
        try {
            store = imap.connect(a);
            f = serverFolder(store, a, r.folder());
            if (f == null) return;
            f.open(Folder.READ_WRITE);
            Message m = f.getMessageByUID(r.uid());
            if (m != null) m.setFlag(flag, on);
        } catch (MessagingException e) {
            throw imap.friendly(e, a.imapHost());
        } finally {
            ImapClient.closeQuietly(f, false);
            ImapClient.closeQuietly(store);
        }
    }

    // ── moves ─────────────────────────────────────────────────────

    public void archive(long userId, long id) {
        move(owned(userId, id), ImapClient.Role.ARCHIVE, "ARCHIVE");
    }

    public void trash(long userId, long id) {
        move(owned(userId, id), ImapClient.Role.TRASH, "TRASH");
    }

    private void move(MessageRepo.Row r, ImapClient.Role role, String code) {
        if (code.equals(r.folder())) return;
        Account a = account(r);
        if (r.uid() == null) {
            messages.moved(r.id(), code, null);
            return;
        }
        Store store = null;
        IMAPFolder src = null;
        try {
            store = imap.connect(a);
            src = serverFolder(store, a, r.folder());
            IMAPFolder dst = imap.folder(store, a, role, true);
            if (src == null || dst == null) throw new ApiException(HttpStatus.CONFLICT, "This mailbox has no " + code.toLowerCase(Locale.ROOT) + " folder.");
            src.open(Folder.READ_WRITE);
            Message m = src.getMessageByUID(r.uid());
            Long newUid = null;
            if (m != null) {
                AppendUID[] res;
                try {
                    res = src.moveUIDMessages(new Message[]{m}, dst);
                } catch (MessagingException noMove) {
                    // Server without MOVE: copy, then delete the original.
                    res = src.copyUIDMessages(new Message[]{m}, dst);
                    m.setFlag(Flags.Flag.DELETED, true);
                    src.expunge(new Message[]{m});
                }
                if (res != null && res.length > 0 && res[0] != null) newUid = res[0].uid;
            }
            messages.moved(r.id(), code, newUid);
        } catch (MessagingException e) {
            throw imap.friendly(e, a.imapHost());
        } finally {
            ImapClient.closeQuietly(src, false);
            ImapClient.closeQuietly(store);
        }
    }

    // ── attachments ───────────────────────────────────────────────

    public record Download(String name, String contentType, byte[] bytes) {}

    public Download attachment(long userId, long id, int index) {
        MessageRepo.Row r = owned(userId, id);
        if (r.uid() == null) throw ApiException.notFound("Attachment");
        Account a = account(r);
        Store store = null;
        IMAPFolder f = null;
        try {
            store = imap.connect(a);
            f = serverFolder(store, a, r.folder());
            if (f == null) throw ApiException.notFound("Attachment");
            f.open(Folder.READ_ONLY);
            Message m = f.getMessageByUID(r.uid());
            if (m == null) throw ApiException.notFound("Message on the server");
            Part p = MessageParser.attachment(m, index);
            if (p == null) throw ApiException.notFound("Attachment");
            Map<String, Object> meta = index < r.attachments().size() ? r.attachments().get(index) : Map.of();
            try (InputStream in = p.getInputStream()) {
                return new Download(String.valueOf(meta.getOrDefault("name", "attachment")),
                        String.valueOf(meta.getOrDefault("contentType", "application/octet-stream")), in.readAllBytes());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw imap.friendly(e, a.imapHost());
        } finally {
            ImapClient.closeQuietly(f, false);
            ImapClient.closeQuietly(store);
        }
    }

    // ── send ──────────────────────────────────────────────────────

    public record Outgoing(long accountId, List<String> to, List<String> cc, List<String> bcc,
                           String subject, String text, Long replyToMessageId) {}

    public MessageRepo.Row send(long userId, Outgoing o, List<MultipartFile> files) {
        Account a = accounts.find(o.accountId()).filter(x -> x.userId() == userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
        if ((o.to() == null || o.to().isEmpty()) && (o.cc() == null || o.cc().isEmpty()) && (o.bcc() == null || o.bcc().isEmpty())) {
            throw ApiException.bad("Add at least one recipient.");
        }
        MessageRepo.Row original = o.replyToMessageId() == null ? null : owned(userId, o.replyToMessageId());
        try {
            var session = imap.smtpSession(a);
            MimeMessage msg = new MimeMessage(session);
            boolean multipart = files != null && files.stream().anyMatch(f -> !f.isEmpty());
            MimeMessageHelper h = new MimeMessageHelper(msg, multipart, "UTF-8");
            h.setFrom(new InternetAddress(a.email(), a.displayName() == null ? a.email() : a.displayName(), "UTF-8"));
            h.setTo(addrs(o.to()));
            if (o.cc() != null && !o.cc().isEmpty()) h.setCc(addrs(o.cc()));
            if (o.bcc() != null && !o.bcc().isEmpty()) h.setBcc(addrs(o.bcc()));
            h.setSubject(o.subject() == null ? "" : o.subject());
            h.setText(o.text() == null ? "" : o.text(), false);
            h.setSentDate(new Date());
            if (multipart) {
                for (MultipartFile f : files) {
                    if (f.isEmpty()) continue;
                    h.addAttachment(f.getOriginalFilename() == null ? "attachment" : f.getOriginalFilename(), f::getInputStream,
                            f.getContentType() == null ? "application/octet-stream" : f.getContentType());
                }
            }
            if (original != null && original.messageId() != null) {
                msg.setHeader("In-Reply-To", original.messageId());
                String refs = original.threadKey() != null && original.threadKey().startsWith("<") && !original.threadKey().equals(original.messageId())
                        ? original.threadKey() + " " + original.messageId() : original.messageId();
                msg.setHeader("References", refs);
            }
            msg.saveChanges();
            Transport.send(msg, a.email(), imap.password(a));

            Long uid = saveToSent(a, msg);
            String messageId = SyncService.trimId(msg.getMessageID());
            String threadKey = original != null ? original.threadKey() : SyncService.threadKey(messageId, null, null, o.subject());
            MessageRepo.NewMessage row = new MessageRepo.NewMessage(a.id(), "SENT", uid, messageId, threadKey,
                    a.displayName(), a.email().toLowerCase(Locale.ROOT), nn(o.to()), nn(o.cc()), o.subject(),
                    MessageParser.snippet(o.text()), o.text(), null, attachmentMeta(files),
                    OffsetDateTime.now(ZoneOffset.UTC), true, false, null, null, null, false);
            Long id = messages.insert(row);
            if (original != null && "INBOX".equals(original.folder()) && !original.seen()) messages.setSeen(original.id(), true);
            return id == null ? null : messages.find(userId, id).orElse(null);
        } catch (ApiException e) {
            throw e;
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "The mail server refused the password for " + a.email() + " when sending.");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't send: " + e.getMessage());
        }
    }

    /** Most IMAP servers (Hostinger included) don't keep a copy of SMTP mail, so we put one in Sent. Gmail does it itself. */
    private Long saveToSent(Account a, MimeMessage msg) {
        if ("google".equals(a.provider())) return null;
        Store store = null;
        try {
            store = imap.connect(a);
            IMAPFolder sent = imap.folder(store, a, ImapClient.Role.SENT, true);
            msg.setFlag(Flags.Flag.SEEN, true);
            AppendUID[] res = sent.appendUIDMessages(new Message[]{msg});
            return res != null && res.length > 0 && res[0] != null ? res[0].uid : null;
        } catch (Exception e) {
            return null; // the mail went out; a missing Sent copy shouldn't fail the send
        } finally {
            ImapClient.closeQuietly(store);
        }
    }

    // ── unsubscribe ───────────────────────────────────────────────

    private static final Pattern ANGLE = Pattern.compile("<([^>]+)>");

    public Map<String, Object> unsubscribe(long userId, long id) {
        MessageRepo.Row r = owned(userId, id);
        if (r.listUnsubscribe() == null) throw ApiException.bad("This sender didn't include an unsubscribe link.");
        String https = null;
        String mailto = null;
        Matcher m = ANGLE.matcher(r.listUnsubscribe());
        while (m.find()) {
            String u = m.group(1).trim();
            if (u.toLowerCase(Locale.ROOT).startsWith("https://") && https == null) https = u;
            else if (u.toLowerCase(Locale.ROOT).startsWith("mailto:") && mailto == null) mailto = u;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (https != null && r.listUnsubscribePost() && oneClick(https)) {
            done(userId, r);
            out.put("status", "done");
            out.put("message", "Unsubscribed. Future mail from this sender goes to Can wait.");
            return out;
        }
        if (mailto != null) {
            sendUnsubscribeMail(userId, r, mailto);
            done(userId, r);
            out.put("status", "done");
            out.put("message", "Unsubscribe request sent. It can take a few days to stop.");
            return out;
        }
        if (https != null) {
            out.put("status", "open_url");
            out.put("url", https);
            out.put("message", "Finish unsubscribing on the sender's page.");
            messages.markUnsubscribed(r.accountId(), r.fromEmail());
            return out;
        }
        throw ApiException.bad("This sender's unsubscribe link isn't usable.");
    }

    private void done(long userId, MessageRepo.Row r) {
        messages.markUnsubscribed(r.accountId(), r.fromEmail());
        if (r.fromEmail() != null) {
            messages.putSenderRule(userId, r.fromEmail(), LaneClassifier.CAN_WAIT);
            messages.applySenderRule(userId, r.fromEmail(), LaneClassifier.CAN_WAIT);
        }
    }

    /** RFC 8058 one-click: POST "List-Unsubscribe=One-Click" to a public https URL. */
    private boolean oneClick(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            for (InetAddress ip : InetAddress.getAllByName(uri.getHost())) {
                if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress()
                        || ip.isMulticastAddress()) return false;
            }
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("List-Unsubscribe=One-Click")).build();
            int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendUnsubscribeMail(long userId, MessageRepo.Row r, String mailto) {
        String rest = mailto.substring("mailto:".length());
        String to = rest;
        String subject = "unsubscribe";
        int q = rest.indexOf('?');
        if (q >= 0) {
            to = rest.substring(0, q);
            for (String kv : rest.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equalsIgnoreCase("subject")) {
                    subject = URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        to = URLDecoder.decode(to, StandardCharsets.UTF_8);
        send(userId, new Outgoing(r.accountId(), List.of(to), List.of(), List.of(), subject, "unsubscribe", null), List.of());
    }

    // ── helpers ───────────────────────────────────────────────────

    private static InternetAddress[] addrs(List<String> list) throws Exception {
        List<InternetAddress> out = new ArrayList<>();
        if (list != null) {
            for (String s : list) {
                if (s == null || s.isBlank()) continue;
                InternetAddress[] parsed = InternetAddress.parse(s.trim(), false);
                for (InternetAddress ia : parsed) {
                    ia.validate();
                    out.add(ia);
                }
            }
        }
        return out.toArray(new InternetAddress[0]);
    }

    private static List<String> nn(List<String> l) {
        return l == null ? List.of() : l;
    }

    private static List<Map<String, Object>> attachmentMeta(List<MultipartFile> files) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", out.size());
            m.put("name", f.getOriginalFilename());
            m.put("size", f.getSize());
            m.put("contentType", f.getContentType());
            out.add(m);
        }
        return out;
    }
}
