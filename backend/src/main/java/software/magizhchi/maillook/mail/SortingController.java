package software.magizhchi.maillook.mail;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The person's own sections and their automatic rules ("Automations"). */
@RestController
@RequestMapping("/api")
public class SortingController {

    public record SectionRequest(String name, String icon, String color) {}

    /** applyNow: also file the mail already in the inbox. */
    public record RuleRequest(String from, String subject, String to, String words, String lane, Boolean markRead,
                              Boolean archive, Boolean enabled, Boolean applyNow) {
        Sorting.RuleIn in() {
            return new Sorting.RuleIn(from, subject, to, words, lane, markRead, archive, enabled);
        }
    }

    private final Sorting sorting;

    public SortingController(Sorting sorting) {
        this.sorting = sorting;
    }

    @GetMapping("/sections")
    public Map<String, Object> sections(@RequestAttribute long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", sorting.sections(userId));
        out.put("icons", Sorting.ICONS);
        out.put("colors", Sorting.COLORS);
        return out;
    }

    @PostMapping("/sections")
    public Sorting.Section createSection(@RequestAttribute long userId, @RequestBody SectionRequest req) {
        return sorting.createSection(userId, req.name(), req.icon(), req.color());
    }

    @PutMapping("/sections/{id}")
    public Sorting.Section updateSection(@RequestAttribute long userId, @PathVariable long id, @RequestBody SectionRequest req) {
        return sorting.updateSection(userId, id, req.name(), req.icon(), req.color());
    }

    @DeleteMapping("/sections/{id}")
    public ResponseEntity<Void> deleteSection(@RequestAttribute long userId, @PathVariable long id) {
        sorting.deleteSection(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rules")
    public Map<String, Object> rules(@RequestAttribute long userId) {
        return Map.of("rules", sorting.rules(userId));
    }

    @PostMapping("/rules")
    public Map<String, Object> createRule(@RequestAttribute long userId, @RequestBody RuleRequest req) {
        Sorting.Rule r = sorting.createRule(userId, req.in(), "user");
        int moved = Boolean.FALSE.equals(req.applyNow()) ? 0 : sorting.applyExisting(userId, r.id());
        return Map.of("rule", sorting.rule(userId, r.id()), "applied", moved);
    }

    @PostMapping("/rules/preview")
    public Map<String, Object> preview(@RequestAttribute long userId, @RequestBody RuleRequest req) {
        return sorting.preview(userId, req.in());
    }

    @PutMapping("/rules/{id}")
    public Sorting.Rule updateRule(@RequestAttribute long userId, @PathVariable long id, @RequestBody RuleRequest req) {
        return sorting.updateRule(userId, id, req.in());
    }

    @PostMapping("/rules/{id}/run")
    public Map<String, Object> run(@RequestAttribute long userId, @PathVariable long id) {
        return Map.of("applied", sorting.applyExisting(userId, id));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@RequestAttribute long userId, @PathVariable long id) {
        sorting.deleteRule(userId, id);
        return ResponseEntity.noContent().build();
    }
}
