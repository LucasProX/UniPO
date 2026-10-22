package com.biecuoguo.realtime;

import com.biecuoguo.config.RealtimeSecuritySchedule;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class RealtimeSecurityScheduleTest {
    @Test
    void sweepChecksBothBoundAndUnauthenticatedConnectionsWithoutAWorkingAuthorityThread() {
        RealtimeHub hub = mock(RealtimeHub.class);
        RealtimeWebSocketHandler handler = mock(RealtimeWebSocketHandler.class);
        RealtimeSecuritySchedule schedule = new RealtimeSecuritySchedule(hub, handler);
        try {
            doThrow(new IllegalStateException("fixture")).when(hub).revalidateConnections();

            schedule.sweep();
            schedule.sweep();

            verify(hub, times(2)).revalidateConnections();
            verify(handler, times(2)).sweepUnauthenticated();
        } finally { schedule.stop(); }
    }
}
