package com.book.download.pdf;

import java.nio.file.Files;
import java.nio.file.Path;

import com.book.entity.TopicDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicPdfSourceTests {
    @TempDir Path directory;

    @Test
    void readsActualStoredLegacyFilenameWithoutRebuildingFromTopicName() throws Exception {
        Path root = Files.createDirectory(directory.resolve("storage"));
        Path legacy = Files.writeString(root.resolve("old-topic-123.pdf"), "fixture");
        TopicDetails topic = topic(legacy.toString());
        topic.setTopicName("Completely different current title");
        assertThat(new TopicPdfSource(root.toString()).resolve(topic)).isEqualTo(legacy.toRealPath());
    }

    @Test
    void fullBookOffsetSourcesUseTheSameStorageConfinement() throws Exception {
        Path root = Files.createDirectory(directory.resolve("book-storage"));
        Path stored = Files.writeString(root.resolve("original.pdf"), "fixture");
        TopicDetails offset = topic(stored.toString());
        offset.setTopicName("Book_offset");
        TopicPdfSource source = new TopicPdfSource(root.toString());
        assertThat(source.resolve(offset)).isEqualTo(stored.toRealPath());
        offset.setPdfPath("../private.pdf");
        assertThatThrownBy(() -> source.resolve(offset)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void relativeStoredPathsResolveInsideStorageRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("storage"));
        Path folder = Files.createDirectory(root.resolve("Book"));
        Path pdf = Files.writeString(folder.resolve("Topic.pdf"), "fixture");
        assertThat(new TopicPdfSource(root.toString()).resolve(topic("Book/Topic.pdf"))).isEqualTo(pdf.toRealPath());
    }

    @Test
    void rejectsOutsidePathsAndTraversal() throws Exception {
        Path root = Files.createDirectory(directory.resolve("storage"));
        Path outside = Files.writeString(directory.resolve("private.pdf"), "private");
        TopicPdfSource source = new TopicPdfSource(root.toString());
        for (String path : new String[]{outside.toString(), "../private.pdf"}) {
            assertThatThrownBy(() -> source.resolve(topic(path)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("outside");
        }
    }

    @Test
    void rejectsUnpublishedMissingAndDirectorySources() throws Exception {
        Path root = Files.createDirectory(directory.resolve("storage"));
        TopicPdfSource source = new TopicPdfSource(root.toString());
        for (String path : new String[]{null, " ", "missing.pdf", root.toString()}) {
            assertThatThrownBy(() -> source.resolve(topic(path))).isInstanceOf(ResponseStatusException.class);
        }
    }

    private TopicDetails topic(String path) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(123L);
        topic.setPdfPath(path);
        return topic;
    }
}
