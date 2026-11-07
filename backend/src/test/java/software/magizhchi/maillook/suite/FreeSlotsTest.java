package software.magizhchi.maillook.suite;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Free meeting times: working hours, two hours' notice, no Sundays, around what's booked, spread over days. */
class FreeSlotsTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, IST).toInstant();
    }

    @Test
    void skipsWhatIsBookedAndSundays() {
        // Saturday 7 Nov, 9 AM. Booked 11 AM–1 PM. Earliest bookable: 11 AM.
        Instant now = at(7, 9, 0);
        List<Instant[]> busy = List.<Instant[]>of(new Instant[]{at(7, 11, 0), at(7, 13, 0)});
        assertThat(MeetPlanner.freeSlots(now, busy, 30, 3))
                .containsExactly(at(7, 13, 0), at(9, 10, 0), at(10, 10, 0));
    }

    @Test
    void aLongMeetingMustFitBeforeSixAndTheNoticeIsTwoHours() {
        // Monday 9 Nov, 3:10 PM: two hours later is 5:10 PM, too late for an hour before 6. Next day instead.
        Instant now = at(9, 15, 10);
        assertThat(MeetPlanner.freeSlots(now, List.of(), 60, 2)).containsExactly(at(10, 10, 0), at(11, 10, 0));
    }

    @Test
    void offersASecondTimeOnTheSameDayWhenDaysRunOut() {
        Instant now = at(9, 6, 0);
        // Only Monday is free; every other day is booked solid.
        List<Instant[]> busy = List.<Instant[]>of(new Instant[]{at(10, 0, 0), at(30, 0, 0)});
        List<Instant> slots = MeetPlanner.freeSlots(now, busy, 30, 3);
        assertThat(slots).hasSize(3).allSatisfy(s -> assertThat(s.atZone(IST).getDayOfMonth()).isEqualTo(9));
        assertThat(slots.get(0)).isEqualTo(at(9, 10, 0));
    }
}
