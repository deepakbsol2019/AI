package com.book.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** UTC wall-clock persistence for legacy DATETIME columns; offset-aware instants at API boundaries. */
public final class UtcDates {
    private UtcDates() { }
    public static LocalDateTime now() { return now(Clock.systemUTC()); }
    public static LocalDateTime now(Clock clock) { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    public static Instant instant(LocalDateTime utc) { return utc == null ? null : utc.toInstant(ZoneOffset.UTC); }
}
