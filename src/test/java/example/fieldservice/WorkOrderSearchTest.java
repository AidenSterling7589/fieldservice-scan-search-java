package example.fieldservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class WorkOrderSearchTest {
    @Test
    void technicianFollowUpTakesPrecedenceOverDispatch() {
        assertEquals("TECHNICIAN_FOLLOW_UP", WorkOrderSearch.dispatchDecision("DISPATCHED", true));
        assertEquals("AWAITING_TECHNICIAN", WorkOrderSearch.dispatchDecision("DISPATCHED", false));
        assertEquals("AWAITING_DISPATCH", WorkOrderSearch.dispatchDecision("PENDING", false));
    }
}
