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
import software.magizhchi.maillook.suite.MeetInvites;

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
    private final MeetInvites invites;
    private final FolderCursors cursors;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public MailActions(AccountRepo accounts, MessageRepo messages, ImapClient imap, MeetInvites invites, FolderCursors cursors) {
        this.invites = invites;
        this.cursors = cursors;
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
            case "SPAM" -> imap.folder(store, a, ImapClient.Role.SPAM, false);
            default -> code.startsWith("F:") ? (IMAPFolder) store.getFolder(code.substring(2)) : null;
        };
    }

    /** Where a move lands: Archive and Trash are made when the mailbox has none; the others must exist. */
    private IMAPFolder destination(Store store, Account a, String code) throws MessagingException {
        return switch (code) {
            case "ARCHIVE" -> imap.folder(store, a, ImapClient.Role.ARCHIVE, true);
            case "TRASH" -> imap.folder(store, a, ImapClient.Role.TRASH, true);
            default -> {
                IMAPFolder f = serverFolder(store, a, code);
                yield f != null && f.exists() ? f : null;
            }
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
        move(owned(userId, id), "ARCHIVE");
    }

    public void trash(long userId, long id) {
        move(owned(userId, id), "TRASH");
    }

    /** Moves mail to Inbox, Archive, Trash, Spam or one of the mailbox's own folders ("F:" + its name). */
    public void moveTo(long userId, long id, String code) {
        MessageRepo.Row r = owned(userId, id);
        move(r, code);
        if (code.startsWith("F:")) cursors.open(r.accountId(), code, code.substring(2));   // keep it in sync from now on
        // Back in the Inbox from Spam or Trash: give it a lane; the AI pass looks at it again.
        if ("INBOX".equals(code) && r.lane() == null) messages.setLane(r.id(), LaneClassifier.FYI, "heuristic");
    }

    private void move(MessageRepo.Row r, String code) {
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
            IMAPFolder dst = destination(store, a, code);
            if (src == null || dst == null) {
                String what = code.startsWith("F:") ? "folder called " + code.substring(2) : code.toLowerCase(Locale.ROOT) + " folder";
                throw new ApiException(HttpStatus.CONFLICT, "This mailbox has no " + what + ".");
            }
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

    /** {@code inviteCardId}: a Meet room booked from this mailbox; the mail then carries its calendar invite. */
    public record Outgoing(long accountId, List<String> to, List<String> cc, List<String> bcc,
                           String subject, String text, Long replyToMessageId, String inviteCardId) {
        public Outgoing(long accountId, List<String> to, List<String> cc, List<String> bcc,
                        String subject, String text, Long replyToMessageId) {
            this(accountId, to, cc, bcc, subject, text, replyToMessageId, null);
        }
    }

    public MessageRepo.Row send(long userId, Outgoing o, List<MultipartFile> files) {
        Account a = accounts.find(o.accountId()).filter(x -> x.userId() == userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
        if ((o.to() == null || o.to().isEmpty()) && (o.cc() == null || o.cc().isEmpty()) && (o.bcc() == null || o.bcc().isEmpty())) {
            throw ApiException.bad("Add at least one recipient.");
        }
        MessageRepo.Row original = o.replyToMessageId() == null ? null : owned(userId, o.replyToMessageId());
        MeetInvites.Booking booking = o.inviteCardId() == null || o.inviteCardId().isBlank() ? null
                : invites.booking(a.id(), o.inviteCardId()).orElseThrow(() -> ApiException.bad("That meeting wasn't booked from this mailbox, so its invite can't go with this mail."));
        try {
            var session = imap.smtpSession(a);
            MimeMessage msg = new MimeMessage(session);
            boolean multipart = files != null && files.stream().anyMatch(f -> !f.isEmpty());
            MimeMessageHelper h = new MimeMessageHelper(msg, multipart && booking == null, "UTF-8");
            h.setFrom(new InternetAddress(a.email(), a.displayName() == null ? a.email() : a.displayName(), "UTF-8"));
            h.setTo(addrs(o.to()));
            if (o.cc() != null && !o.cc().isEmpty()) h.setCc(addrs(o.cc()));
            if (o.bcc() != null && !o.bcc().isEmpty()) h.setBcc(addrs(o.bcc()));
            h.setSubject(o.subject() == null ? "" : o.subject());
            h.setText(o.text() == null ? "" : o.text(), false);
            h.setSentDate(new Date());
            if (booking != null) {
                List<String> people = new ArrayList<>(MeetInvites.addresses(nn(o.to())));
                people.addAll(MeetInvites.addresses(nn(o.cc())));
                msg.setContent(withInvite(o.text(), invites.ics(booking, a.email(), a.displayName(), people), files));
            } else if (multipart) {
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
            Transport.send(msg, a.email(), imap.secret(a));

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

    /**
     * Answers a meeting invite: a short mail to the organizer carrying the iCalendar REPLY, so their calendar
     * marks this person as going, maybe or not going. A copy goes in Sent like any other mail.
     */
    public void sendCalendarReply(long userId, MessageRepo.Row invite, String to, String subject, String text, String ics) {
        Account a = account(invite);
        if (a.userId() != userId) throw ApiException.notFound("Account");
        try {
            var session = imap.smtpSession(a);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(a.email(), a.displayName() == null ? a.email() : a.displayName(), "UTF-8"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject(subject, "UTF-8");
            msg.setSentDate(new Date());
            var alt = new jakarta.mail.internet.MimeMultipart("alternative");
            var plain = new jakarta.mail.internet.MimeBodyPart();
            plain.setText(text, "UTF-8");
            alt.addBodyPart(plain);
            var cal = new jakarta.mail.internet.MimeBodyPart();
            cal.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(
                    ics.getBytes(StandardCharsets.UTF_8), "text/calendar; charset=UTF-8; method=REPLY")));
            alt.addBodyPart(cal);
            msg.setContent(alt);
            if (invite.messageId() != null) {
                msg.setHeader("In-Reply-To", invite.messageId());
                msg.setHeader("References", invite.messageId());
            }
            msg.saveChanges();
            Transport.send(msg, a.email(), imap.secret(a));
            saveToSent(a, msg);
        } catch (ApiException e) {
            throw e;
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "The mail server refused the password for " + a.email() + " when sending.");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't send your answer: " + e.getMessage());
        }
    }

    /**
     * The shape calendar apps look for: the text and the invite as alternatives (Gmail and Outlook show
     * Yes / No / Maybe), the same invite as an invite.ics file, then any files the person attached.
     */
    static jakarta.mail.internet.MimeMultipart withInvite(String text, String ics, List<MultipartFile> files) throws Exception {
        byte[] cal = ics.getBytes(StandardCharsets.UTF_8);
        var alt = new jakarta.mail.internet.MimeMultipart("alternative");
        var plain = new jakarta.mail.internet.MimeBodyPart();
        plain.setText(text == null ? "" : text, "UTF-8");
        alt.addBodyPart(plain);
        var inline = new jakarta.mail.internet.MimeBodyPart();
        inline.setDataHandler(new jakarta.activation.DataHandler(
                new jakarta.mail.util.ByteArrayDataSource(cal, "text/calendar; charset=UTF-8; method=REQUEST")));
        alt.addBodyPart(inline);
        var mixed = new jakarta.mail.internet.MimeMultipart("mixed");
        var body = new jakarta.mail.internet.MimeBodyPart();
        body.setContent(alt);
        mixed.addBodyPart(body);
        var file = new jakarta.mail.internet.MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(cal, "application/ics")));
        file.setFileName("invite.ics");
        file.setDisposition(Part.ATTACHMENT);
        mixed.addBodyPart(file);
        for (MultipartFile f : files == null ? List.<MultipartFile>of() : files) {
            if (f.isEmpty()) continue;
            var p = new jakarta.mail.internet.MimeBodyPart();
            p.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(f.getBytes(),
                    f.getContentType() == null ? "application/octet-stream" : f.getContentType())));
            p.setFileName(f.getOriginalFilename() == null ? "attachment" : f.getOriginalFilename());
            p.setDisposition(Part.ATTACHMENT);
            mixed.addBodyPart(p);
        }
        return mixed;
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
