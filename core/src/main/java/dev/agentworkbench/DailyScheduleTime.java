package dev.agentworkbench;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;

/**
 * Daily schedule clock rules using the saved IANA zone and local date.
 */
final class DailyScheduleTime {
    private DailyScheduleTime() { }

    static Due resolve(String zoneName, String timeText, LocalDate date) {
        ZoneId zone = zone(zoneName);
        LocalTime time = time(timeText);
        LocalDateTime local = date.atTime(time);
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.isEmpty()) return null;
        Instant instant = local.toInstant(offsets.getFirst());
        return new Due(date.toString(), instant);
    }

    static ZoneOffsetTransition gap(String zoneName, String timeText, LocalDate date) {
        LocalDateTime local = date.atTime(time(timeText));
        return zone(zoneName).getRules().getTransition(local);
    }

    static ZoneId zone(String value) {
        if (!ZoneId.getAvailableZoneIds().contains(value)) throw new ApiException(400, "timezone 必须是有效的 IANA 时区标识");
        return ZoneId.of(value);
    }

    static LocalTime time(String value) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) throw new ApiException(400, "localTime 必须使用 HH:mm 24 小时格式");
        return LocalTime.parse(value);
    }

    record Due(String occurrenceKey, Instant instant) { }
}
