package com.book.help;

import java.time.Instant;

public record ChatMessage(long messageId, long pageId, String senderRole, String content,
        Instant createdAt, long messageOrder) { }
