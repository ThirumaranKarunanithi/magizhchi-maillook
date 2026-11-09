package software.magizhchi.maillook.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.suite.BoxClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The person's Magizhchi Box contact book: the master copy, shared with the Box app on their phone.
 * MailLook reads it with the Box connection it already has, and adds to it by uploading a new card.
 */
@Service
public class BoxContacts {

    private static final Logger log = LoggerFactory.getLogger(BoxContacts.class);
    /**
     * How long a listing is trusted before asking Box again. Asking is cheap (one file list; only new
     * backup files are read), so a contact added on the phone shows here within seconds.
     */
    private static final long FRESH_MS = 10_000;

    /** stamp changes whenever the backup files in Box change, so the page knows to reload. */
    public record Book(boolean connected, String error, List<Vcards.Card> cards, int files, String stamp) {
        static Book notConnected() {
            return new Book(false, null, List.of(), 0, "");
        }
    }

    private record Cached(long at, String sig, Book book) {}

    private final BoxClient box;
    private final Map<Long, Cached> books = new ConcurrentHashMap<>();
    /** Backup files never change, so their text is kept (file id → text), least recently used out first. */
    private final Map<Long, String> texts = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> e) {
            return size() > 3000;
        }
    });

    public BoxContacts(BoxClient box) {
        this.box = box;
    }

    public boolean available() {
        return box.configured();
    }

    public boolean connected(long userId) {
        return box.existing(userId).isPresent();
    }

    /** Connects this person to their Box (if needed) so the book can be read. */
    public Book connect(long userId) {
        box.link(userId);
        books.remove(userId);
        return book(userId);
    }

    public void forget(long userId) {
        books.remove(userId);
    }

    public Book book(long userId) {
        Cached c = books.get(userId);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < FRESH_MS) return c.book();
        if (box.existing(userId).isEmpty()) return Book.notConnected();

        BoxClient.Reply r = box.get(userId, "/api/files/mirror-list?prefix=" + URLEncoder.encode(Vcards.FOLDER, StandardCharsets.UTF_8));
        if (!r.ok() || r.json() == null) {
            // Keep showing what we had; say why only when there is nothing.
            if (c != null) return c.book();
            String why = r.code() == 0 ? "Can't reach Magizhchi Box right now." : "Magizhchi Box answered " + r.code() + " for your contacts.";
            return new Book(true, why, List.of(), 0, "");
        }
        JsonNode rows = r.json().isArray() ? r.json() : r.json().path("items");
        List<JsonNode> vcfs = new ArrayList<>();
        for (JsonNode f : rows) {
            if (f.path("fileName").asText("").toLowerCase(Locale.ROOT).endsWith(".vcf")) vcfs.add(f);
        }
        vcfs.sort(Comparator.comparing(f -> f.path("sentAt").asText("")));
        StringBuilder sig = new StringBuilder();
        for (JsonNode f : vcfs) sig.append(f.path("id").asLong()).append(',');
        if (c != null && c.sig().equals(sig.toString())) {
            books.put(userId, new Cached(now, c.sig(), c.book()));
            return c.book();
        }

        List<Vcards.Card> all = new ArrayList<>();
        for (JsonNode f : vcfs) {
            long id = f.path("id").asLong();
            String text = texts.get(id);
            if (text == null) {
                BoxClient.Reply u = box.get(userId, "/api/files/" + id + "/preview-url");
                String url = u.ok() && u.json() != null ? u.json().path("url").asText(null) : null;
                text = box.fetchText(url);
                if (text == null) {
                    log.debug("Couldn't read contacts file {} from Box", id);
                    continue;           // one unreadable backup shouldn't hide the rest
                }
                texts.put(id, text);
            }
            all.addAll(Vcards.parse(text));
        }
        List<Vcards.Card> cards = new ArrayList<>(Vcards.merge(all).values());
        cards.sort(Comparator.comparing(x -> x.name == null ? "~" : x.name.toLowerCase(Locale.ROOT)));
        Book b = new Book(true, null, List.copyOf(cards), vcfs.size(), Integer.toHexString(sig.toString().hashCode()) + "-" + vcfs.size());
        books.put(userId, new Cached(now, sig.toString(), b));
        return b;
    }

    /** Cards that list this email. */
    public List<Vcards.Card> withEmail(long userId, String email) {
        String e = email.toLowerCase(Locale.ROOT);
        return book(userId).cards().stream().filter(c -> c.emails.stream().anyMatch(x -> x.equalsIgnoreCase(e))).toList();
    }

    /**
     * Saves someone to the Box book. When a card with the same name already exists, the email (and
     * phone) are added to it with a replacement card, so nobody is listed twice.
     * @return true when an existing card was updated.
     */
    public boolean add(long userId, String name, String email, String phone, String org) {
        box.link(userId);
        Book b = book(userId);
        String n = name == null || name.isBlank() ? email : name.trim();
        Vcards.Card same = b.cards().stream().filter(c -> c.name != null && c.name.trim().equalsIgnoreCase(n)).findFirst().orElse(null);
        Vcards.Card card = new Vcards.Card();
        List<String> replaces = List.of();
        if (same != null) {
            card.name = same.name;
            card.tels.addAll(same.tels);
            card.emails.addAll(same.emails);
            card.org = same.org;
            card.bday = same.bday;
            card.address = same.address;
            card.note = same.note;
            card.photo = same.photo;
            replaces = List.of(same.key());
        } else {
            card.name = n;
        }
        if (email != null && card.emails.stream().noneMatch(x -> x.equalsIgnoreCase(email))) card.emails.add(email);
        if (phone != null && !phone.isBlank() && card.tels.stream().noneMatch(t -> Vcards.normTel(t).equals(Vcards.normTel(phone)))) {
            card.tels.add(phone.trim());
        }
        if (card.org == null && org != null && !org.isBlank()) card.org = org.trim();
        if (same != null && card.key().equals(same.key()) && card.emails.size() == same.emails.size()
                && card.tels.size() == same.tels.size() && java.util.Objects.equals(card.org, same.org)) {
            return true;    // nothing new to say
        }
        String file = "maillook-" + System.currentTimeMillis() + ".vcf";
        try {
            box.upload(userId, file, "text/vcard", Vcards.build(card, replaces).getBytes(StandardCharsets.UTF_8), Vcards.FOLDER);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Box couldn't save the contact.");
        }
        books.remove(userId);
        return same != null;
    }
}
