package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.common.Json;
import software.magizhchi.maillook.common.SecretBox;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saves files to the person's own Magizhchi Box drive. Nobody pastes a token: Box's server-to-server
 * POST /api/provision/workspace (guarded by MAGIZHCHI_BOX_PROVISION_SECRET, the same one Forms and CRM
 * use) finds or creates the Box account for their Magizhchi ID email and issues an mbk_ token labelled
 * "Magizhchi MailLook" in their Connected Apps. MailLook keeps it encrypted and connects on first use.
 *
 * Wire protocol, as Box's guide and sdk-java describe it:
 *   POST /api/files/send/{driveId}  multipart "file" + "folderPath" (missing folders are created)
 *   GET  /api/storage/usage         space, plan and plan state
 * Box answers 413 both when the account is out of space (or its plan has lapsed) and when one file is
 * too big; MailLook tells those apart and never retries them.
 */
@Component
public class BoxClient {

    private static final Logger log = LoggerFactory.getLogger(BoxClient.class);
    static final String APP_NAME = "Magizhchi MailLook";

    public record Reply(int code, JsonNode json, String raw) {
        public boolean ok() {
            return code >= 200 && code < 300;
        }

        public String message() {
            if (json == null) return raw == null || raw.isBlank() || raw.length() > 300 ? null : raw.trim();
            String m = json.path("message").asText(json.path("error").asText(""));
            return m.isBlank() ? null : m;
        }
    }

    /** A person's connection: their Box token and the id of their personal drive. */
    public record Link(String apiKey, long driveId) {}

    private final String api;
    private final String web;
    private final String secret;
    private final JdbcTemplate jdbc;
    private final MailLookUsers users;
    private final SecretBox crypto;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public BoxClient(@Value("${magizhchi.apps.box.api:}") String api,
                     @Value("${magizhchi.apps.box.web:}") String web,
                     @Value("${magizhchi.apps.box.provision-secret:}") String secret,
                     JdbcTemplate jdbc, MailLookUsers users, SecretBox crypto) {
        this.api = api == null ? "" : api.trim().replaceAll("/+$", "");
        this.web = web == null || web.isBlank() ? this.api : web.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
        this.jdbc = jdbc;
        this.users = users;
        this.crypto = crypto;
    }

    /** True when this server can connect people to Box (URL and provisioning secret are set). */
    public boolean configured() {
        return !api.isBlank() && !secret.isBlank();
    }

    /** The Box page that opens this person's drive, or Box's home page when they aren't connected. */
    public String webUrl(Long driveId) {
        if (web.isBlank()) return null;
        return driveId == null ? web : web + "/?c=" + driveId;
    }

    /** The stored connection, without connecting. */
    public Optional<Link> existing(long userId) {
        return jdbc.query("SELECT box_api_key_enc, box_drive_id FROM users WHERE id = ?", (rs, i) -> {
            String enc = rs.getString(1);
            long drive = rs.getLong(2);
            if (enc == null || enc.isBlank() || rs.wasNull()) return null;
            String key = crypto.decrypt(enc);
            return key == null ? null : new Link(key, drive);
        }, userId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** The person's connection, connecting them to their own Box drive first if needed. */
    public Link link(long userId) {
        Optional<Link> have = existing(userId);
        if (have.isPresent()) return have.get();
        requireConfigured();
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            have = existing(userId);
            return have.orElseGet(() -> provision(userId));
        }
    }

    /** Uploads one file into folderPath (created as needed) and returns Box's file id. */
    public long upload(long userId, String fileName, String contentType, byte[] bytes, String folderPath) {
        Link link = link(userId);
        Reply r = sendFile(link, fileName, contentType, bytes, folderPath);
        if (r.code() == 401) {
            // The token was revoked in Box (Connected Apps): connect again once, then retry.
            forget(userId);
            link = link(userId);
            r = sendFile(link, fileName, contentType, bytes, folderPath);
        }
        if (r.code() == 413) {
            String said = r.raw() == null ? "" : r.raw().toLowerCase();
            if (said.contains("storage limit") || said.contains("upgrade")) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Your Magizhchi Box is full or its plan has ended, so new files can't be saved. Recharge in Magizhchi Box to save more.");
            }
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "This file is too big for Magizhchi Box.");
        }
        if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi Box right now. Try again in a minute.");
        if (!r.ok()) {
            String said = r.message();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box couldn't save the file (" + r.code() + ")" + (said == null ? "." : ": " + said));
        }
        long id = r.json() == null ? 0 : r.json().path("id").asLong(0);
        if (id == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box saved the file but sent no file id back.");
        return id;
    }

    /** Space, plan and plan state from Box (null when not connected yet). */
    public JsonNode usage(long userId) {
        Optional<Link> link = existing(userId);
        if (link.isEmpty()) return null;
        Reply r = send("GET", "/api/storage/usage", link.get().apiKey(), null, null);
        if (r.code() == 401) {
            forget(userId);
            return null;
        }
        if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi Box right now.");
        if (!r.ok()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box answered " + r.code() + " for your storage.");
        return r.json();
    }

    /** A GET on Box with this person's stored key (no connecting). Code 0 = not connected or unreachable. */
    public Reply get(long userId, String path) {
        Optional<Link> link = existing(userId);
        if (link.isEmpty()) return new Reply(0, null, "not connected");
        Reply r = send("GET", path, link.get().apiKey(), null, null);
        if (r.code() == 401) forget(userId);
        return r;
    }

    /** Downloads a presigned file link Box handed out (needs no key). Null when it can't. */
    public String fetchText(String url) {
        if (url == null || url.isBlank()) return null;
        String full = url.startsWith("http") ? url : api + (url.startsWith("/") ? "" : "/") + url;
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(full)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) return null;
            return new String(res.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Box download failed: {}", e.toString());
            return null;
        }
    }

    /** Drops the stored connection (Box revoked it); the next save connects again. */
    public void forget(long userId) {
        jdbc.update("UPDATE users SET box_api_key_enc = NULL, box_drive_id = NULL, box_connected_at = NULL WHERE id = ?", userId);
    }

    // ── internals ─────────────────────────────────────────────────

    private void requireConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Magizhchi Box isn't set up on this server yet (MAGIZHCHI_BOX_PROVISION_SECRET).");
        }
    }

    private Link provision(long userId) {
        SuiteTokens.Identity who = users.identity(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "Sign in with Magizhchi ID to save files to Magizhchi Box."));
        Map<String, Object> body = Map.of("email", who.email().trim().toLowerCase(),
                "name", who.name() == null ? "" : who.name(), "appName", APP_NAME);
        Reply r = send("POST", "/api/provision/workspace", null, Map.of("X-Provision-Secret", secret), body);
        if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi Box right now. Try again in a minute.");
        if (r.code() == 401 || r.code() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Magizhchi Box didn't accept MailLook's connection. MAGIZHCHI_BOX_PROVISION_SECRET must match Box's.");
        }
        if (!r.ok() || r.json() == null) {
            String said = r.message();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box couldn't connect your account (" + r.code() + ")" + (said == null ? "." : ": " + said));
        }
        String key = r.json().path("apiKey").asText("");
        if (key.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box connected you but sent no access key back.");
        long drive = r.json().path("conversationId").asLong(0);
        if (drive == 0) {
            Reply p = send("GET", "/api/conversations/personal", key, null, null);
            drive = p.ok() && p.json() != null ? p.json().path("id").asLong(0) : 0;
            if (drive == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box didn't say which drive is yours.");
        }
        jdbc.update("UPDATE users SET box_api_key_enc = ?, box_drive_id = ?, box_connected_at = ? WHERE id = ?",
                crypto.encrypt(key), drive, OffsetDateTime.now(), userId);
        log.info("MailLook user {} connected to Magizhchi Box (drive {})", userId, drive);
        return new Link(key, drive);
    }

    private Reply sendFile(Link link, String fileName, String contentType, byte[] bytes, String folderPath) {
        String boundary = "----MailLook" + UUID.randomUUID().toString().replace("-", "");
        String name = fileName == null || fileName.isBlank() ? "attachment" : fileName.replaceAll("[\\r\\n\"]", "_");
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.split(";")[0].trim();
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 1024);
        writePart(out, boundary, "Content-Disposition: form-data; name=\"folderPath\"\r\n\r\n", folderPath.getBytes(StandardCharsets.UTF_8));
        writePart(out, boundary, "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n", bytes);
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/api/files/send/" + link.driveId()))
                    .timeout(Duration.ofMinutes(5))
                    .header("X-Api-Key", link.apiKey())
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();
            return read(http.send(req, HttpResponse.BodyHandlers.ofString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, null, e.getMessage());
        } catch (Exception e) {
            log.debug("Box upload failed: {}", e.toString());
            return new Reply(0, null, e.getMessage());
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String headers, byte[] content) {
        out.writeBytes(("--" + boundary + "\r\n" + headers).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private Reply send(String method, String path, String apiKey, Map<String, String> headers, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json");
            if (apiKey != null) b.header("X-Api-Key", apiKey);
            if (headers != null) headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return read(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, null, e.getMessage());
        } catch (Exception e) {
            log.debug("Box call {} {} failed: {}", method, path, e.toString());
            return new Reply(0, null, e.getMessage());
        }
    }

    private Reply read(HttpResponse<String> res) {
        JsonNode json = null;
        try {
            json = res.body() == null || res.body().isBlank() ? null : mapper.readTree(res.body());
        } catch (Exception ignored) {
        }
        return new Reply(res.statusCode(), json, res.body());
    }

    /**
     * Searches inside the documents in the person's own Box drive (Box reads PDFs, Office files, text and
     * scans and keeps their words): GET /api/conversations/{drive}/content-search. Code 0 = not connected
     * to Box, or Box unreachable.
     */
    public Reply searchInside(long userId, String q) {
        Optional<Link> link = existing(userId);
        if (link.isEmpty()) return new Reply(0, null, "not connected");
        return get(userId, "/api/conversations/" + link.get().driveId() + "/content-search?q="
                + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
    }

    /** One page of the files in a folder of their Box drive, newest first (Box's Spring page: content, last). */
    public Reply listFiles(long userId, String folderPrefix, int page, int size) {
        Optional<Link> link = existing(userId);
        if (link.isEmpty()) return new Reply(0, null, "not connected");
        return get(userId, "/api/conversations/" + link.get().driveId() + "/files?page=" + page + "&size=" + size
                + (folderPrefix == null ? "" : "&folderPrefix=" + java.net.URLEncoder.encode(folderPrefix, StandardCharsets.UTF_8)));
    }

    /** Files in their Box whose name (or caption) holds q; Box returns up to 30 across all their drives. */
    public Reply searchNames(long userId, String q) {
        if (existing(userId).isEmpty()) return new Reply(0, null, "not connected");
        return get(userId, "/api/files/search?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
    }

    /** A short-lived link that downloads one Box file. */
    public Optional<String> downloadUrl(long userId, long fileId) {
        Reply r = get(userId, "/api/files/" + fileId + "/download-url");
        if (!r.ok() || r.json() == null) return Optional.empty();
        String url = r.json().path("url").asText("");
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    /** A short-lived link to view one Box file in the browser (Box checks it is the person's). */
    public Optional<String> previewUrl(long userId, long fileId) {
        Reply r = get(userId, "/api/files/" + fileId + "/preview-url");
        if (!r.ok() || r.json() == null) return Optional.empty();
        String url = r.json().path("url").asText("");
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    /** Which attachment each Box file came from, for files MailLook saved: boxFileId → [messageId, index]. */
    public Map<Long, long[]> savedFrom(List<Long> boxFileIds) {
        if (boxFileIds.isEmpty()) return Map.of();
        String in = String.join(",", boxFileIds.stream().map(id -> "?").toList());
        Map<Long, long[]> out = new java.util.HashMap<>();
        jdbc.query("SELECT box_file_id, message_id, att_index FROM box_saves WHERE box_file_id IN (" + in + ")",
                rs -> { out.putIfAbsent(rs.getLong(1), new long[]{rs.getLong(2), rs.getInt(3)}); }, boxFileIds.toArray());
        return out;
    }

    /** Ids of the saved attachments of these messages, as "messageId:index". */
    public List<String> savedKeys(List<Long> messageIds) {
        if (messageIds.isEmpty()) return List.of();
        String in = String.join(",", messageIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT message_id, att_index FROM box_saves WHERE message_id IN (" + in + ")",
                (rs, i) -> rs.getLong(1) + ":" + rs.getInt(2), messageIds.toArray());
    }

    public Optional<Long> savedFile(long messageId, int index) {
        return jdbc.queryForList("SELECT box_file_id FROM box_saves WHERE message_id = ? AND att_index = ?", Long.class, messageId, index)
                .stream().findFirst();
    }

    public void recordSave(long messageId, int index, long fileId, String folderPath) {
        jdbc.update("INSERT INTO box_saves (message_id, att_index, box_file_id, folder_path) VALUES (?, ?, ?, ?)",
                messageId, index, fileId, folderPath);
    }
}
