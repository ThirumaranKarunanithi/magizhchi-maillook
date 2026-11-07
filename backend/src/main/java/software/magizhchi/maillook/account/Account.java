package software.magizhchi.maillook.account;

import java.time.OffsetDateTime;

/**
 * A connected mailbox, as stored. {@code passwordEnc} never leaves the server: the mailbox password,
 * or for {@code auth = "oauth"} the Google / Microsoft refresh token, encrypted.
 */
public record Account(
        long id, long userId, String email, String displayName, String color, String provider,
        String imapHost, int imapPort, String smtpHost, int smtpPort, String passwordEnc,
        String status, OffsetDateTime lastSyncAt, String lastError,
        Long inboxUidValidity, Long inboxLastUid, Long sentUidValidity, Long sentLastUid, String auth) {

    public boolean oauth() {
        return "oauth".equals(auth);
    }
}
