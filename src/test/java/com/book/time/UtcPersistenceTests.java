package com.book.time;

import java.time.*;
import com.book.entity.AuthorDetails;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","debug=false","spring.jpa.show-sql=false"})
class UtcPersistenceTests {
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @Test void physicalEventColumnAndApiInstantRepresentUtcWithoutApplyingTheJvmOffsetAgain() {
        Instant before=Instant.now().minusSeconds(2);
        var author=new AuthorDetails();author.setAuthorName("UTC event fixture");entities.persist(author);entities.flush();
        var raw=jdbc.queryForObject("SELECT created_at FROM pdf_file_author_details WHERE aouthor_id=?",
                (rs,n)->rs.getObject(1,LocalDateTime.class),author.getAuthorId());
        assertThat(raw.toInstant(ZoneOffset.UTC)).isBetween(before,Instant.now().plusSeconds(2));
        entities.clear();var loaded=entities.find(AuthorDetails.class,author.getAuthorId());
        assertThat(UtcDates.instant(loaded.getCreatedAt())).isEqualTo(raw.toInstant(ZoneOffset.UTC));
    }
}
