package com.reveil.reminder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ReminderSchedulerTest {

    @Test
    void tickSwallowsErrorsSoTheSchedulerKeepsRunning() {
        ReminderService service = mock(ReminderService.class);
        when(service.sendDueReminders()).thenThrow(new IllegalStateException("mongo indisponible"));
        new ReminderScheduler(service).tick();
        verify(service).sendDueReminders();
    }
}
