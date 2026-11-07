package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The small rules behind slice 4: which mails are one report series, and which meetings could have been an email. */
class AcademyRulesTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reportsWithDifferentDatesAreOneSeries() {
        String a = DigestController.stem("Daily report – 5 Nov 2026");
        assertThat(a).isEqualTo("daily report");
        assertThat(DigestController.stem("Re: Daily Report - Nov 6th")).isEqualTo(a);
        assertThat(DigestController.stem("FW: daily report (06/11/2026, Friday)")).isEqualTo(a);
        assertThat(DigestController.stem("Sprint 14 status update")).isEqualTo(DigestController.stem("Sprint 15 status update"));
        assertThat(DigestController.stem("Weekly report")).isNotEqualTo(a);
        assertThat(DigestController.title("Re: Daily report – 5 Nov 2026")).isEqualTo("Daily report");
        assertThat(DigestController.title("Standup notes, Mon 2 Nov")).isEqualTo("Standup notes");
        assertThat(DigestController.title("12/11/2026")).isEqualTo("Team reports");
    }

    @Test
    void picksTheStandupRoomThatSharesAWord() {
        List<Map<String, Object>> rooms = List.of(Map.of("id", "a", "name", "Payments standup"), Map.of("id", "b", "name", "Mobile scrum"));
        assertThat(DigestController.guessRoom(rooms, "Mobile team daily report")).isEqualTo("b");
        assertThat(DigestController.guessRoom(rooms, "Daily report")).isNull();               // two rooms, no clue: let them pick
        assertThat(DigestController.guessRoom(rooms.subList(0, 1), "Daily report")).isEqualTo("a");
        assertThat(DigestController.guessRoom(List.of(), "Daily report")).isNull();
    }

    @Test
    void aMeetingThatCameToNothingCouldHaveBeenAnEmail() throws Exception {
        assertThat(MeetRecaps.couldBeEmail(JSON.readTree(
                "{\"hasTranscript\":true,\"summary\":\"The team went over last week's numbers. Nothing changed.\",\"decisions\":[],\"actions\":[]}"), 30)).isTrue();
        // Something was decided, or someone has a task: it earned its time.
        assertThat(MeetRecaps.couldBeEmail(JSON.readTree(
                "{\"hasTranscript\":true,\"summary\":\"Short.\",\"decisions\":[\"Ship on Friday\"],\"actions\":[]}"), 30)).isFalse();
        assertThat(MeetRecaps.couldBeEmail(JSON.readTree(
                "{\"hasTranscript\":true,\"summary\":\"Short.\",\"decisions\":[],\"actions\":[{\"owner\":\"You\",\"task\":\"Call the bank\"}]}"), 30)).isFalse();
        // A long summary means a real discussion.
        assertThat(MeetRecaps.couldBeEmail(JSON.readTree(
                "{\"hasTranscript\":true,\"summary\":\"One. Two. Three.\",\"decisions\":[],\"actions\":[]}"), 30)).isFalse();
        // No captions, or a two-minute hello: nothing to judge.
        assertThat(MeetRecaps.couldBeEmail(JSON.readTree("{\"hasTranscript\":false,\"summary\":\"Hi.\"}"), 30)).isFalse();
        assertThat(MeetRecaps.couldBeEmail(JSON.readTree("{\"hasTranscript\":true,\"summary\":\"Hi.\",\"decisions\":[],\"actions\":[]}"), 2)).isFalse();
    }

    @Test
    void onlyTheBuildingOwnerOrARoomAdminRunsARoom() throws Exception {
        assertThat(MeetClient.canRun(JSON.readTree("{\"myRole\":\"SUPER_ADMIN\"}"), "me")).isTrue();
        assertThat(MeetClient.canRun(JSON.readTree("{\"myRole\":\"ADMIN\"}"), "me")).isTrue();
        // The building is theirs (rooms stand in their maker's building), even without a seat in the room.
        assertThat(MeetClient.canRun(JSON.readTree("{\"myRole\":null,\"ownerUserId\":\"me\"}"), "me")).isTrue();
        assertThat(MeetClient.canRun(JSON.readTree("{\"myRole\":\"MEMBER\",\"ownerUserId\":\"someone\"}"), "me")).isFalse();
        assertThat(MeetClient.canRun(JSON.readTree("{\"myRole\":\"GUEST\"}"), "me")).isFalse();
        assertThat(MeetClient.canRun(JSON.readTree("{\"ownerUserId\":\"me\"}"), null)).isFalse();
        assertThat(MeetClient.canRun(null, "me")).isFalse();
    }

    @Test
    void eventTimesAreReadInTheMailsZone() {
        String day = LocalDate.now().plusDays(3).toString();
        assertThat(MeetController.eventStart(day + "T10:00", null)).isEqualTo(java.time.LocalDateTime.parse(day + "T10:00")
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().toString());
        assertThat(MeetController.eventStart(day + "T10:00", "Europe/London")).isEqualTo(java.time.LocalDateTime.parse(day + "T10:00")
                .atZone(java.time.ZoneId.of("Europe/London")).toInstant().toString());
        assertThat(MeetController.eventStart(day + "T10:00:00", "Not/AZone")).isNotNull();
        assertThat(MeetController.eventStart("2020-01-01T10:00", null)).isNull();      // already over
        assertThat(MeetController.eventStart("next Tuesday", null)).isNull();
        assertThat(MeetController.eventStart(null, null)).isNull();
    }
}
