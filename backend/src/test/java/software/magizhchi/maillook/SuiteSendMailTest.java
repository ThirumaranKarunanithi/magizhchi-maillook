package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.DraftRepo;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.mail.Outbox;
import software.magizhchi.maillook.mail.Waiting;
import software.magizhchi.maillook.suite.BoxClient;
import software.magizhchi.maillook.suite.BoxController;
import software.magizhchi.maillook.suite.SuiteBrainController;
import software.magizhchi.maillook.suite.SuiteDelegationVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The assistant's send_mail tool: it queues (so Undo works), checks addresses, and refuses read-only tokens. */
class SuiteSendMailTest {

    private SuiteDelegationVerifier verifier;
    private JdbcTemplate jdbc;
    private AccountRepo accounts;
    private Outbox outbox;
    private MailActions mailActions;
    private BoxClient box;
    private BoxController boxController;
    private SuiteBrainController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(SuiteDelegationVerifier.class);
        jdbc = mock(JdbcTemplate.class);
        accounts = mock(AccountRepo.class);
        outbox = mock(Outbox.class);
        mailActions = mock(MailActions.class);
        box = mock(BoxClient.class);
        boxController = mock(BoxController.class);
        controller = new SuiteBrainController(verifier, jdbc, mock(MessageRepo.class), mock(Waiting.class),
                mock(DraftRepo.class), accounts, outbox, mailActions, box, boxController, new ObjectMapper().findAndRegisterModules(), "secret");
        when(jdbc.queryForList(anyString(), eq(Long.class), anyString())).thenReturn(List.of(7L));
    }

    private void token(String... scopes) {
        when(verifier.verify("t")).thenReturn(new SuiteDelegationVerifier.Delegation("me@example.com", Set.of(scopes)));
    }

    private Object send(Map<String, Object> input) {
        return controller.run("send_mail", Map.of("input", input), "Bearer t");
    }

    private static Map<String, Object> mail(Object to) {
        return Map.of("to", to, "subject", "test", "body", "test");
    }

    @Test
    void sendQueuesTheMailForTheUndoWindowAndOffersUndo() {
        token("read", "write");
        Account a = mock(Account.class);
        when(a.id()).thenReturn(3L);
        when(accounts.forUser(7L)).thenReturn(List.of(a));
        Outbox.Item item = new Outbox.Item(42L, 7L, 3L, new ObjectMapper().createObjectNode(), null, "queued", null, null);
        when(outbox.queue(anyLong(), any(ObjectNode.class), any(MailActions.Outgoing.class), anyList(), any(Instant.class))).thenReturn(item);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) send(mail(List.of("pugal@magizhchi.academy")));

        assertEquals(true, r.get("ok"));
        assertEquals(42L, r.get("outboxId"));
        assertEquals(20, r.get("undoSeconds"));
        @SuppressWarnings("unchecked")
        Map<String, Object> undo = (Map<String, Object>) r.get("undo");
        assertEquals("cancel_send", undo.get("tool"));

        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).queue(eq(7L), any(ObjectNode.class), any(MailActions.Outgoing.class), anyList(), when.capture());
        assertTrue(when.getValue().isAfter(Instant.now().plusSeconds(10)), "must wait out the undo window, not send instantly");
    }

    private Outbox.Item queuedItem() {
        Account a = mock(Account.class);
        when(a.id()).thenReturn(3L);
        when(accounts.forUser(7L)).thenReturn(List.of(a));
        Outbox.Item item = new Outbox.Item(42L, 7L, 3L, new ObjectMapper().createObjectNode(), null, "queued", null, null);
        when(outbox.queue(anyLong(), any(ObjectNode.class), any(MailActions.Outgoing.class), anyList(), any(Instant.class))).thenReturn(item);
        return item;
    }

    @Test
    void sendLaterQueuesAtThatTimeAndSetsAReplyReminder() {
        token("read", "write");
        queuedItem();
        Instant at = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Map<String, Object> input = Map.of("to", List.of("a@b.co"), "subject", "s", "body", "b",
                "sendAt", at.toString(), "remindIfNoReplyDays", 3);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) send(input);

        assertEquals(at.toString(), r.get("sendAt"));
        assertEquals(0, r.get("undoSeconds"));
        ArgumentCaptor<ObjectNode> payload = ArgumentCaptor.forClass(ObjectNode.class);
        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).queue(eq(7L), payload.capture(), any(MailActions.Outgoing.class), anyList(), when.capture());
        assertEquals(at, when.getValue());
        assertEquals(at.plus(java.time.Duration.ofDays(3)).toString(), payload.getValue().path("remindAt").asText());
    }

    @Test
    void aSendTimeInThePastIsRefused() {
        token("read", "write");
        queuedItem();
        Map<String, Object> input = Map.of("to", List.of("a@b.co"), "subject", "s", "body", "b",
                "sendAt", Instant.now().minusSeconds(60).toString());
        assertThrows(ApiException.class, () -> send(input));
        assertThrows(ApiException.class, () -> send(Map.of("to", List.of("a@b.co"), "subject", "s", "body", "b", "sendAt", "tomorrow morning")));
    }

    @Test
    void replyKeepsTheConversationAndAddsRe() {
        token("read", "write");
        queuedItem();
        MessageRepo.Row original = mock(MessageRepo.Row.class);
        when(original.accountId()).thenReturn(3L);
        when(original.fromEmail()).thenReturn("ravi@example.com");
        when(original.subject()).thenReturn("Quote for Friday");
        when(mailActions.owned(7L, 99L)).thenReturn(original);

        Object r = controller.run("reply_mail", Map.of("input", Map.of("messageId", 99, "to", List.of(), "body", "Yes, Friday works.")), "Bearer t");

        assertTrue(r instanceof Map);
        ArgumentCaptor<MailActions.Outgoing> o = ArgumentCaptor.forClass(MailActions.Outgoing.class);
        verify(outbox).queue(eq(7L), any(ObjectNode.class), o.capture(), anyList(), any(Instant.class));
        assertEquals(List.of("ravi@example.com"), o.getValue().to());
        assertEquals("Re: Quote for Friday", o.getValue().subject());
        assertEquals(99L, o.getValue().replyToMessageId());
    }

    // ------------------------------------------------------------------ Box files on an email

    private void mailbox() {
        Account a = mock(Account.class);
        when(a.id()).thenReturn(3L);
        when(accounts.forUser(7L)).thenReturn(List.of(a));
        Outbox.Item item = new Outbox.Item(42L, 7L, 3L, new ObjectMapper().createObjectNode(), null, "queued", null, null);
        when(outbox.queue(anyLong(), any(ObjectNode.class), any(MailActions.Outgoing.class), anyList(), any(Instant.class))).thenReturn(item);
    }

    private static BoxClient.Reply boxReply(int code, String json) throws Exception {
        return new BoxClient.Reply(code, json == null ? null : new ObjectMapper().readTree(json), json);
    }

    private Map<String, Object> sendWithBox(Object boxFiles) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(Map.of("to", List.of("karnish@example.com"), "subject", "Inheritance PPT", "body", "Here it is."));
        input.put("boxFiles", boxFiles);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) send(input);
        return r;
    }

    @Test
    void aBoxFileIsPulledFromTheUsersBoxAndAttachedToTheMail() throws Exception {
        token("read", "write");
        mailbox();
        when(box.get(7L, "/api/files/11/download-url")).thenReturn(boxReply(200, "{\"url\":\"https://s3.example/x\"}"));
        when(box.fetchBytes(eq("https://s3.example/x"), anyLong())).thenReturn(new byte[]{1, 2, 3, 4});

        Map<String, Object> r = sendWithBox(List.of(Map.of("fileId", 11, "fileName", "Inheritance in java.pptx")));

        assertEquals(List.of("Inheritance in java.pptx"), r.get("attached"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MailActions.OutFile>> files = ArgumentCaptor.forClass(List.class);
        verify(outbox).queue(eq(7L), any(ObjectNode.class), any(MailActions.Outgoing.class), files.capture(), any(Instant.class));
        assertEquals(1, files.getValue().size());
        assertEquals(4, files.getValue().get(0).bytes().length);
        assertTrue(files.getValue().get(0).contentType().contains("presentation") || files.getValue().get(0).contentType().equals("application/octet-stream"));
    }

    @Test
    void filesThatCannotBeAttachedAreRefusedWithTheReason() throws Exception {
        token("read", "write");
        mailbox();
        when(box.get(7L, "/api/files/1/download-url")).thenReturn(boxReply(400, "{\"message\":\"This file is stored as chunks.\"}"));
        when(box.get(7L, "/api/files/2/download-url")).thenReturn(boxReply(403, null));
        when(box.get(7L, "/api/files/3/download-url")).thenReturn(boxReply(404, null));
        when(box.get(7L, "/api/files/4/download-url")).thenReturn(boxReply(200, "{\"url\":\"u\"}"));
        when(box.fetchBytes(eq("u"), anyLong())).thenReturn(null); // too big

        assertTrue(assertThrows(ApiException.class, () -> sendWithBox(List.of(Map.of("fileId", 1, "fileName", "a.pptx")))).getMessage().contains("desktop app"));
        assertTrue(assertThrows(ApiException.class, () -> sendWithBox(List.of(Map.of("fileId", 2, "fileName", "b.pdf")))).getMessage().contains("view-only"));
        assertTrue(assertThrows(ApiException.class, () -> sendWithBox(List.of(Map.of("fileId", 3, "fileName", "c.pdf")))).getMessage().contains("can't find"));
        assertTrue(assertThrows(ApiException.class, () -> sendWithBox(List.of(Map.of("fileId", 4, "fileName", "d.zip")))).getMessage().contains("too large"));
        verify(outbox, never()).queue(anyLong(), any(), any(), anyList(), any());
    }

    @Test
    void moreThanFiveBoxFilesOrABadIdIsRefused() throws Exception {
        token("read", "write");
        mailbox();
        List<Object> six = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(Map.of("fileId", i, "fileName", "f" + i));
        assertThrows(ApiException.class, () -> sendWithBox(six));
        assertThrows(ApiException.class, () -> sendWithBox(List.of(Map.of("fileName", "no id"))));
        assertThrows(ApiException.class, () -> sendWithBox(List.of(Map.of("fileId", "abc", "fileName", "x"))));
    }

    @Test
    void fileNamesAreMadeSafe() {
        assertEquals("a_b_c.pptx", SuiteBrainController.cleanFileName("a/b\\c.pptx", 1));
        assertEquals("box-file-9", SuiteBrainController.cleanFileName("  ", 9));
        assertTrue(SuiteBrainController.cleanFileName("x".repeat(300), 1).length() <= 120);
    }

    // ------------------------------------------------------------------ everyday mailbox actions

    @Test
    void archivingAMailIsDoneAndCanBeUndone() {
        token("read", "write");
        MessageRepo.Row row = mock(MessageRepo.Row.class);
        when(row.fromEmail()).thenReturn("vinith@example.com");
        when(row.subject()).thenReturn("Application for UI/UX Designer");
        when(mailActions.owned(7L, 55L)).thenReturn(row);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.run("mail_action",
                Map.of("input", Map.of("messageId", 55, "action", "archive")), "Bearer t");

        verify(mailActions).archive(7L, 55L);
        assertTrue(String.valueOf(r.get("message")).startsWith("Archived"));
        @SuppressWarnings("unchecked")
        Map<String, Object> undo = (Map<String, Object>) r.get("undo");
        assertEquals("mail_action", undo.get("tool"));
        @SuppressWarnings("unchecked")
        Map<String, Object> undoInput = (Map<String, Object>) undo.get("input");
        assertEquals("inbox", undoInput.get("action"));
    }

    @Test
    void flagAndReadStatesAreTogglesAndUnknownActionsAreRefused() {
        token("read", "write");
        MessageRepo.Row row = mock(MessageRepo.Row.class);
        when(mailActions.owned(7L, 55L)).thenReturn(row);
        controller.run("mail_action", Map.of("input", Map.of("messageId", 55, "action", "flag")), "Bearer t");
        verify(mailActions).setFlagged(7L, 55L, true);
        controller.run("mail_action", Map.of("input", Map.of("messageId", 55, "action", "unread")), "Bearer t");
        verify(mailActions).setSeen(7L, 55L, false);
        assertThrows(ApiException.class, () -> controller.run("mail_action", Map.of("input", Map.of("messageId", 55, "action", "shred")), "Bearer t"));
    }

    @Test
    void mailboxChangesNeedAWriteToken() {
        token("read");
        assertThrows(ApiException.class, () -> controller.run("mail_action", Map.of("input", Map.of("messageId", 55, "action", "archive")), "Bearer t"));
        assertThrows(ApiException.class, () -> controller.run("save_attachment_to_box", Map.of("input", Map.of("messageId", 55)), "Bearer t"));
        verify(mailActions, never()).archive(anyLong(), anyLong());
    }

    @Test
    void savingAttachmentsToBoxUsesTheExistingBoxSaver() {
        token("read", "write");
        when(boxController.saveAll(7L, 55L)).thenReturn(Map.of("message", "Saved 2 files."));
        when(boxController.saveOne(7L, 55L, 1)).thenReturn(Map.of("message", "Saved one."));

        assertEquals("Saved 2 files.", ((Map<?, ?>) controller.run("save_attachment_to_box", Map.of("input", Map.of("messageId", 55)), "Bearer t")).get("message"));
        assertEquals("Saved one.", ((Map<?, ?>) controller.run("save_attachment_to_box", Map.of("input", Map.of("messageId", 55, "attachmentIndex", 1)), "Bearer t")).get("message"));
    }

    @Test
    void aReadOnlyTokenCannotSend() {
        token("read");
        assertThrows(ApiException.class, () -> send(mail(List.of("a@b.co"))));
        verify(outbox, never()).queue(anyLong(), any(), any(), anyList(), any());
    }

    @Test
    void badOrMissingAddressesAreRefused() {
        token("read", "write");
        assertThrows(ApiException.class, () -> send(mail(List.of("not an address"))));
        assertThrows(ApiException.class, () -> send(mail(List.of())));
        assertThrows(ApiException.class, () -> send(mail(List.of("a@b.co\r\nBcc: x@y.zz"))));
        verify(outbox, never()).queue(anyLong(), any(), any(), anyList(), any());
    }

    @Test
    void moreThanFiveRecipientsAreRefused() {
        token("read", "write");
        assertThrows(ApiException.class, () -> send(mail(List.of("a@b.co", "b@b.co", "c@b.co", "d@b.co", "e@b.co", "f@b.co"))));
    }

    @Test
    void theManifestListsSendMailAsDangerousAndHidesTheHelpers() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) controller.manifest("secret").get("tools");
        Map<String, Object> send = tools.stream().filter(t -> "send_mail".equals(t.get("name"))).findFirst().orElseThrow();
        assertEquals("danger", send.get("risk"));
        assertTrue(tools.stream().filter(t -> "cancel_send".equals(t.get("name"))).allMatch(t -> Boolean.TRUE.equals(t.get("hidden"))));
        assertTrue(tools.stream().filter(t -> "known_recipients".equals(t.get("name"))).allMatch(t -> Boolean.TRUE.equals(t.get("hidden"))));
    }
}
