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
    private SuiteBrainController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(SuiteDelegationVerifier.class);
        jdbc = mock(JdbcTemplate.class);
        accounts = mock(AccountRepo.class);
        outbox = mock(Outbox.class);
        mailActions = mock(MailActions.class);
        controller = new SuiteBrainController(verifier, jdbc, mock(MessageRepo.class), mock(Waiting.class),
                mock(DraftRepo.class), accounts, outbox, mailActions, new ObjectMapper().findAndRegisterModules(), "secret");
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
