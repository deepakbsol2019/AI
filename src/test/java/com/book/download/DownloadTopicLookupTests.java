package com.book.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;

@DataJpaTest(showSql = false, properties = { "spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
class DownloadTopicLookupTests {
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;

    @Test
    void selectedLookupExcludesForeignBookAndMissingIdsAtTheDatabase() {
        BookDetails first = savedBook("First");
        BookDetails second = savedBook("Second");
        Long firstId = first.getTopics().getFirst().getTopicId();
        Long secondId = second.getTopics().getFirst().getTopicId();

        List<TopicDetails> result = topics.findAllByBookDetailsBookIdAndTopicIdIn(first.getBookId(),
                List.of(secondId, firstId, Long.MAX_VALUE));

        assertThat(result).extracting(TopicDetails::getTopicId).containsExactly(firstId);
        assertThat(result).allSatisfy(topic -> assertThat(topic.getBookDetails().getBookId())
                .isEqualTo(first.getBookId()));
    }

    private BookDetails savedBook(String name) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Writer");
        BookDetails book = new BookDetails();
        book.setExtractionId(UUID.randomUUID().toString());
        book.setBookName(name + UUID.randomUUID());
        book.setTotalPages(1);
        book.setAuthorDetails(author);
        TopicDetails topic = new TopicDetails();
        topic.setTopicName("Topic");
        book.addTopic(topic);
        return books.saveAndFlush(book);
    }
}
