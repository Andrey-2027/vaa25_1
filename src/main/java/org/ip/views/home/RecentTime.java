package org.ip.views.home;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Время открытия для колонки «Открыто»: «5 мин назад», «сегодня, 09:12», «вчера, 16:40», «13.11, 09:12». */
final class RecentTime {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SAME_YEAR = DateTimeFormatter.ofPattern("dd.MM, HH:mm");
    private static final DateTimeFormatter OTHER_YEAR = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

    private RecentTime() {}

    static String format(Instant at, Instant now, ZoneId zone) {
        Duration age = Duration.between(at, now);
        if (age.isNegative() || age.toMinutes() < 1) return "только что";
        if (age.toMinutes() < 60) return age.toMinutes() + " мин назад";
        ZonedDateTime time = at.atZone(zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate day = time.toLocalDate();
        if (day.equals(today)) return "сегодня, " + TIME.format(time);
        if (day.equals(today.minusDays(1))) return "вчера, " + TIME.format(time);
        return (day.getYear() == today.getYear() ? SAME_YEAR : OTHER_YEAR).format(time);
    }
}
