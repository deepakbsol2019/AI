package com.book.download.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.book.download.dto.DownloadBookModel;
import com.book.download.pdf.BookPdfMerger;
import com.book.download.pdf.BookPdfMerger.SourcePdf;
import com.book.download.pdf.FrontMatterRenderer;
import com.book.download.pdf.TopicPdfSource;
import com.book.download.service.DownloadService;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import com.book.user.security.TenantContext;

/** Local download assembly from the current book's committed metadata and stored PDFs. */
@Service
public class DownloadServiceImpl implements DownloadService {
    private final BookDetailsRepository books;
    private final TopicDetailsRepository topics;
    private final TopicPdfSource sources;
    private final FrontMatterRenderer frontMatter;
    private final BookPdfMerger merger;

    public DownloadServiceImpl(BookDetailsRepository books, TopicDetailsRepository topics,
            TopicPdfSource sources, FrontMatterRenderer frontMatter, BookPdfMerger merger) {
        this.books = books;
        this.topics = topics;
        this.sources = sources;
        this.frontMatter = frontMatter;
        this.merger = merger;
    }

    @Override
    @Transactional(readOnly = true)
    @Tool(name = "downloadBook", returnDirect = true, description = """
            Download the complete PDF of an existing book using its persisted book ID.
            Includes the original front matter and all saved topics in book order, without adding a new cover.
            Returns PDF bytes for the application to deliver to the user. Do not guess book IDs.
            """)
    public byte[] downloadBook(
            @ToolParam(description = "Required positive book ID from an existing book record or book search result.")
            Long bookId) {
        BookDetails book = requireBook(bookId);
        List<TopicDetails> found = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(bookId);
        if (found.isEmpty()) {
            throw integrity("Book " + bookId + " has no saved topic PDFs.");
        }
        validateOwnership(found, bookId);
        List<TopicDetails> offsets = found.stream().filter(DownloadServiceImpl::isOffset).toList();
        if (offsets.size() > 1) {
            throw integrity("Book " + bookId + " has multiple topics ending in _offset; its PDF order is ambiguous.");
        }
        boolean wholeBook = isWholeBookFallback(book, found);
        if (offsets.isEmpty() && !wholeBook) {
            throw integrity("Book " + bookId + " is missing its required _offset topic PDF.");
        }
        if (found.size() == offsets.size() && !wholeBook) {
            throw integrity("Book " + bookId + " has an _offset PDF but no saved chapter PDFs.");
        }
        List<TopicDetails> ordered = new ArrayList<>(found.size());
        ordered.addAll(offsets);
        found.stream().filter(topic -> !isOffset(topic))
                .sorted(Comparator.comparing(TopicDetails::getTopicId)).forEachOrdered(ordered::add);
        return merger.merge(null, resolveSources(ordered));
    }

    @Override
    @Transactional(readOnly = true)
    @Tool(name = "downloadTopics", returnDirect = true, description = """
            Download selected topics from one existing book as a single PDF, with a generated cover and contents.
            Preserves the supplied topic ID order. For one topic, supply a list containing that single ID.
            Returns PDF bytes for the application to deliver to the user. Do not guess book or topic IDs.
            """)
    public byte[] downloadTopics(
            @ToolParam(description = "Required positive book ID owning every selected topic, from an existing book record or book search result.")
            Long bookId,
            @ToolParam(description = "Required nonempty ordered list of unique positive topic IDs belonging to bookId. PDF pages and contents follow this exact order; only explicitly selected topics are included.")
            List<Long> topicIds) {
        requirePositiveId(bookId, "bookId");
        List<Long> requested = validateSelection(topicIds);
        BookDetails book = requireBook(bookId);
        List<TopicDetails> found = topics.findAllByBookDetailsBookIdAndTopicIdIn(bookId, requested);
        validateOwnership(found, bookId);
        Map<Long, TopicDetails> byId = new HashMap<>();
        for (TopicDetails topic : found) {
            byId.put(topic.getTopicId(), topic);
        }
        if (byId.size() != requested.size() || !byId.keySet().containsAll(requested)) {
            // Deliberately use one response for absent IDs and another book's IDs.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Every requested topic must exist and belong to book " + bookId + ".");
        }
        List<TopicDetails> ordered = requested.stream().map(byId::get).toList();
        List<SourcePdf> paths = resolveSources(ordered);
        AuthorDetails author = book.getAuthorDetails();
        DownloadBookModel model = new DownloadBookModel(
                new DownloadBookModel.Book(book.getBookId(), book.getBookName()),
                author == null ? null : new DownloadBookModel.Author(author.getAuthorName()),
                ordered.stream().map(topic -> new DownloadBookModel.Topic(
                        topic.getTopicId(), topic.getTopicName())).toList());
        return merger.merge(frontMatter.render(model), paths);
    }

    private BookDetails requireBook(Long bookId) {
        requirePositiveId(bookId, "bookId");
        TenantContext.requireTenantId();
        BookDetails book = books.findById(bookId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Book " + bookId + " was not found."));
        TenantContext.requireOwned(book.getTenantId());
        return book;
    }

    private List<SourcePdf> resolveSources(List<TopicDetails> ordered) {
        return ordered.stream().map(topic -> new SourcePdf(sources.resolve(topic),
                isOffset(topic) && Objects.equals(topic.getStartPage(), 1)
                        && Objects.equals(topic.getEndPage(), 0))).toList();
    }

    private static List<Long> validateSelection(List<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topicIds must contain at least one topic ID.");
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : topicIds) {
            requirePositiveId(id, "Each topic ID");
            if (!seen.add(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topicIds must not contain duplicate IDs.");
            }
        }
        return List.copyOf(topicIds);
    }

    private static void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a positive integer.");
        }
    }

    private static void validateOwnership(List<TopicDetails> found, Long bookId) {
        Set<Long> ids = new HashSet<>();
        for (TopicDetails topic : found) {
            if (topic == null || topic.getTopicId() == null || topic.getTopicId() <= 0
                    || topic.getBookDetails() == null
                    || !Objects.equals(topic.getBookDetails().getBookId(), bookId)
                    || !ids.add(topic.getTopicId())) {
                throw integrity("The saved topic metadata is inconsistent for book " + bookId + ".");
            }
        }
    }

    private static boolean isOffset(TopicDetails topic) {
        return topic.getTopicName() != null && topic.getTopicName().endsWith("_offset");
    }

    /** A full-range reserved offset holds the original book; keep legacy saved rows readable. */
    private static boolean isWholeBookFallback(BookDetails book, List<TopicDetails> found) {
        if (found.size() != 1 || book.getTotalPages() <= 0) return false;
        TopicDetails topic = found.getFirst();
        boolean canonicalOffset = Objects.equals(topic.getTopicName(), book.getBookName() + "_offset")
                && Objects.equals(topic.getDetails(), book.getBookName() + "_offset detail");
        boolean legacyWholeBook = Objects.equals(topic.getTopicName(), book.getBookName())
                && Objects.equals(topic.getDetails(), book.getBookDetails());
        return (canonicalOffset || legacyWholeBook)
                && Objects.equals(topic.getStartPage(), 1)
                && Objects.equals(topic.getEndPage(), book.getTotalPages())
                && topic.getPrintedStartPage() == null && topic.getPrintedEndPage() == null;
    }

    private static ResponseStatusException integrity(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
