package software.magizhchi.maillook.account;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.magizhchi.maillook.common.ApiException;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** The royal signature designer's endpoints, and the public address mail loads the banner from. */
@RestController
@RequestMapping("/api")
public class SignatureController {

    private final RoyalSignatures royal;
    private final AccountService accounts;

    public SignatureController(RoyalSignatures royal, AccountService accounts) {
        this.royal = royal;
        this.accounts = accounts;
    }

    /**
     * Multipart: {@code design} (JSON: template, layout, name, title, company, phone, whatsapp, email, website,
     * address, tagline, booking, social links, photo/logo tokens, photoCrop), {@code image} (the banner, PNG or
     * JPEG) and, when newly chosen, {@code photo} and {@code logo}.
     */
    @PutMapping(path = "/accounts/{id}/signature-design", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> save(@RequestAttribute long userId, @PathVariable long id,
                                    @RequestParam("design") String design, @RequestParam("image") MultipartFile image,
                                    @RequestParam(value = "photo", required = false) MultipartFile photo,
                                    @RequestParam(value = "logo", required = false) MultipartFile logo) throws IOException {
        royal.save(userId, id, design, image == null ? null : image.getBytes(),
                photo == null ? null : photo.getBytes(), logo == null ? null : logo.getBytes());
        return view(id);
    }

    /** The round icons under a signature, in the template's colour (six hex digits). */
    @GetMapping("/sig/icon/{name}/{color}")
    public ResponseEntity<byte[]> icon(@PathVariable String name, @PathVariable String color) {
        byte[] png = SignatureIcons.png(name, color.replaceFirst("\\.png$", ""));
        if (png == null) throw ApiException.notFound("Icon");
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .body(png);
    }

    @DeleteMapping("/accounts/{id}/signature-design")
    public Map<String, Object> clear(@RequestAttribute long userId, @PathVariable long id) {
        royal.clear(userId, id);
        return view(id);
    }

    @GetMapping("/sig/{token}")
    public ResponseEntity<byte[]> picture(@PathVariable String token) {
        RoyalSignatures.Picture p = royal.picture(token.replaceFirst("\\.(png|jpe?g)$", "")).orElseThrow(() -> ApiException.notFound("Picture"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(p.data());
    }

    private Map<String, Object> view(long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("signature", accounts.signature(id));
        out.putAll(royal.view(id));
        return out;
    }
}
