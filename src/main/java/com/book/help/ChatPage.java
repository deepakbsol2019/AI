package com.book.help;

import java.time.Instant;

/** Private metadata only; page listings never carry conversation bodies. */
public record ChatPage(long pageId, String name, String description, Instant createdAt,
        Instant updatedAt, long revision) { }
