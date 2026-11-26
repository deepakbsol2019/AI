package com.book.time;

import java.time.*;
import java.util.List;
import com.book.dto.BookSearchResponse;
import com.book.entity.AuditedEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class UtcDatesTests {
    @Test void eventClockZoneCannotChangeStoredUtcWallTime() {
        Instant event=Instant.parse("2026-07-15T12:34:56.123456Z");
        for(String zone:List.of("Asia/Kolkata","Europe/London","Pacific/Auckland")){
            var value=UtcDates.now(Clock.fixed(event,ZoneId.of(zone)));
            assertThat(value).isEqualTo(LocalDateTime.parse("2026-07-15T12:34:56.123456"));
            assertThat(UtcDates.instant(value)).isEqualTo(event);
        }
    }
    @Test void eventCallbacksGenerateUtcOnTheServerAndPreserveOriginalCreationTime() {
        var entity=new Fixture();Instant before=Instant.now().minusSeconds(1);entity.create();
        assertThat(UtcDates.instant(entity.getCreatedAt())).isBetween(before,Instant.now().plusSeconds(1));
        var created=entity.getCreatedAt();entity.update();assertThat(entity.getCreatedAt()).isEqualTo(created);
        assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(created);
    }
    @Test void metadataApisEmitExplicitUtcOffsetsIncludingDetachedNestedMetadata() {
        var event=Instant.parse("2026-07-15T12:34:56.123456Z");
        var author=new BookSearchResponse.AuthorResult(1L,"Author",event,event);
        var topic=new BookSearchResponse.TopicResult(2L,"Topic","Details",1,1,1,1,event,event);
        var book=new BookSearchResponse.BookResult(3L,"Book","Programming","Author","Details",1,4L,event,event,author,1,List.of(topic));
        String json=JsonMapper.builder().build().writeValueAsString(book);
        assertThat(json).contains("\"createdAt\":\"2026-07-15T12:34:56.123456Z\"","\"updatedAt\":\"2026-07-15T12:34:56.123456Z\"");
        var entity=new Fixture();entity.create();String direct=JsonMapper.builder().build().writeValueAsString(entity);
        assertThat(direct).containsPattern("\"createdAt\":\"[^\"]+Z\"");
        assertThat(UtcDates.instant(null)).isNull();
    }
    static class Fixture extends AuditedEntity {void create(){onCreate();}void update(){onUpdate();}}
}
