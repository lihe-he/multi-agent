package dev.agentworkbench;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class DailyScheduleTimeTest {
    @Test
    void skipsNonexistentSpringForwardTimeAndChoosesFirstFallBackOffset() {
        assertNull(DailyScheduleTime.resolve("Europe/Berlin", "02:30", LocalDate.of(2026, 3, 29)));

        var due = DailyScheduleTime.resolve("Europe/Berlin", "02:30", LocalDate.of(2026, 10, 25));
        var offsets = ZoneId.of("Europe/Berlin").getRules().getValidOffsets(LocalDate.of(2026, 10, 25).atTime(2, 30));
        assertEquals(2, offsets.size());
        assertEquals("2026-10-25", due.occurrenceKey());
        assertEquals(LocalDate.of(2026, 10, 25).atTime(2, 30).toInstant(offsets.getFirst()), due.instant());
    }

    @Test
    void acceptsOnlyIanaZonesAndStrictLocalTimes() {
        assertEquals(400, assertThrows(ApiException.class, () -> DailyScheduleTime.zone("+02:00")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> DailyScheduleTime.time("9:00")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> DailyScheduleTime.time("24:00")).status());
    }
}
