package software.magizhchi.maillook.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CcLaneTest {

    private static final String ME = "thirumarankarunanithi@magizhchi.academy";

    @Test
    void copiedMailStaysOutOfNeedsMeUnlessItNamesTheOwner() {
        // The student's leave mail: to HR, the owner only in cc.
        assertEquals(LaneClassifier.FYI, LaneClassifier.keepCcOutOfNeedsMe(LaneClassifier.NEEDS_ME, ME,
                List.of("hr@magizhchi.com"), "Today iam getting to leave\nGood Evening Sir. I need to take my father to the hospital"));
        // Written to the owner: the AI's Needs me stands.
        assertEquals(LaneClassifier.NEEDS_ME, LaneClassifier.keepCcOutOfNeedsMe(LaneClassifier.NEEDS_ME, ME,
                List.of("hr@magizhchi.com", "Thirumarankarunanithi@magizhchi.academy"), "Leave today"));
        // Copied, but asks the owner by name.
        assertEquals(LaneClassifier.NEEDS_ME, LaneClassifier.keepCcOutOfNeedsMe(LaneClassifier.NEEDS_ME, ME,
                List.of("hr@magizhchi.com"), "Leave today\nThirumaran sir, please approve"));
        // Other lanes are left as the AI chose.
        assertEquals(LaneClassifier.CAN_WAIT, LaneClassifier.keepCcOutOfNeedsMe(LaneClassifier.CAN_WAIT, ME,
                List.of("hr@magizhchi.com"), "Offer"));
        // Short words never count as a name ("sir", "team").
        assertEquals(LaneClassifier.FYI, LaneClassifier.keepCcOutOfNeedsMe(LaneClassifier.NEEDS_ME, ME,
                List.of("hr@magizhchi.com"), "Dear team, sir, karu"));
    }
}
