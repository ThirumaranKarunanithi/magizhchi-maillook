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
    private SuiteBrainController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(SuiteDelegationVerifier.class);
        jdbc = mock(JdbcTemplate.class);
        accounts = mock(AccountRepo.class);
        outbox = mock(Outbox.class);
        controller = new SuiteBrainController(verifier, jdbc, mock(MessageRepo.class), mock(Waiting.class),
                mock(DraftRepo.class), accounts, outbox, new ObjectMapper().findAndRegisterModules(), "secret");
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
