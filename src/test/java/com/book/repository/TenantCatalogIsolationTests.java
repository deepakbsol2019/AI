package com.book.repository;

import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Files;
import com.book.dto.BookSearchCriteria;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.metadata.dto.MetadataPatchRequest;
import com.book.metadata.service.BookMetadataService;
import com.book.download.service.impl.DownloadServiceImpl;
import com.book.download.service.BookReaderService;
import com.book.download.pdf.TopicPdfSource;
import com.book.download.pdf.BookPdfMerger;
import com.book.download.pdf.FrontMatterRenderer;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.upload.service.BookUploadStatusService;
import com.book.user.security.TenantContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(showSql=false, properties={"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@Import({BookSearchQueryRepository.class, BookMetadataService.class})
class TenantCatalogIsolationTests {
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookSearchQueryRepository search;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired BookUploadStatusRepository receipts;
    @Autowired BookMetadataService metadata;
    @TempDir Path storage;

    @Test void actualPagedSearchCountsOnlyOwnedRowsAndQuarantinesUnassignedHistoricalBooks() {
        BookDetails own;
        try (var ignored=TenantContext.use(1)) { own=book("Same title"); }
        try (var ignored=TenantContext.use(2)) { book("Same title"); }
        BookDetails historical;
        try (var ignored=TenantContext.use(3)) { historical=book("Unassigned historical"); }
        entityManager.flush();
        jdbc.update("UPDATE book_details SET tenant_id=NULL WHERE book_id=?", historical.getBookId());
        entityManager.clear();
        try (var ignored=TenantContext.use(1)) {
            var result=search.search(new BookSearchCriteria(null,null,null,null,null,null,0,5));
            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.books()).extracting(BookDetails::getBookId).containsExactly(own.getBookId());
            assertThat(books.existsByCanonicalBookName("same title")).isTrue();
            assertThat(books.existsByCanonicalBookName("Unassigned historical")).isFalse();
        }
        assertThatThrownBy(() -> search.search(new BookSearchCriteria(null,null,null,null,null,null,0,5)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }
    @Test void foreignAndUnassignedBooksCannotBeEditedOrDownloaded() {
        BookDetails foreign;
        try (var ignored=TenantContext.use(2)) { foreign=book("Private tenant book"); }
        entityManager.flush();
        var download=new DownloadServiceImpl(books,topics,mock(com.book.download.pdf.TopicPdfSource.class),
                mock(com.book.download.pdf.FrontMatterRenderer.class),mock(com.book.download.pdf.BookPdfMerger.class));
        MetadataPatchRequest request=JsonMapper.builder().build().readValue("{\"bookType\":\"Physics\"}",MetadataPatchRequest.class);
        try (var ignored=TenantContext.use(1)) {
            assertThatThrownBy(() -> metadata.patchBook(foreign.getBookId(),request)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> download.downloadBook(foreign.getBookId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> download.downloadTopics(foreign.getBookId(),List.of(1L))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThat(foreign.getBookType()).isEqualTo("Unknown");
        }
        jdbc.update("UPDATE book_details SET tenant_id=NULL WHERE book_id=?",foreign.getBookId()); entityManager.clear();
        try (var ignored=TenantContext.use(1)) {
            assertThatThrownBy(() -> download.downloadBook(foreign.getBookId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
    }
    @Test void uploadReceiptReadsAndTerminalChangesAreScopedToItsOwningTenant() {
        BookUploadStatus receipt;
        try (var ignored=TenantContext.use(2)) {
            receipt=new BookUploadStatus(); receipt.setStatus(BookUploadStatus.IN_PROGRESS); receipt.setMessage("Private upload.pdf");
            entityManager.persist(receipt); entityManager.flush();
        }
        try (var ignored=TenantContext.use(1)) {
            assertThat(receipts.readCurrent(receipt.getBookUploadId())).isEmpty();
            assertThat(receipts.finishIfInProgress(receipt.getBookUploadId(),BookUploadStatus.IN_PROGRESS,BookUploadStatus.FAILED,"Spoofed")).isZero();
            assertThatThrownBy(() -> new BookUploadStatusService(receipts).get(receipt.getBookUploadId()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        try (var ignored=TenantContext.use(2)) {
            assertThat(receipts.readCurrent(receipt.getBookUploadId()).orElseThrow().getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
        }
    }

    @Test void superUserSearchIsTenantScopedBeforePaginationAndCounts() {
        BookDetails own;
        try(var scope=TenantContext.use(1)){own=book("Scoped search own");}
        try(var scope=TenantContext.use(2)){book("Scoped search foreign");}
        try(var scope=TenantContext.use(1,true)){
            var page=search.search(new BookSearchCriteria("scoped search",null,null,null,null,null,0,1));
            assertThat(page.totalElements()).isEqualTo(1);
            assertThat(page.books()).extracting(BookDetails::getBookId).containsExactly(own.getBookId());
            assertThat(search.search(new BookSearchCriteria("scoped search",null,null,null,null,null,1,1)).books()).isEmpty();
            assertThat(search.search(new BookSearchCriteria("foreign",null,null,null,null,null,0,5)).totalElements()).isZero();
        }
    }

    @Test void superUserCannotReadOrEditForeignFilesButTenantOwnerCan() throws Exception {
        BookDetails foreign;Long topicId;
        try(var scope=TenantContext.use(2)){
            foreign=book("Foreign book");foreign.setTotalPages(2);
            var topic=foreign.getTopics().getFirst();topic.setTopicName("Foreign book_offset");
            topic.setPdfPath(pdf("prefix.pdf").toString());
            var chapter=new TopicDetails();chapter.setTopicName("Chapter");chapter.setStartPage(2);chapter.setEndPage(2);
            chapter.setPdfPath(pdf("owned.pdf").toString());foreign.addTopic(chapter);entityManager.persist(chapter);
            entityManager.flush();topicId=chapter.getTopicId();
        }
        var download=new DownloadServiceImpl(books,topics,new TopicPdfSource(storage.toString()),mock(FrontMatterRenderer.class),new BookPdfMerger());
        var reader=new BookReaderService(topics,download);
        try(var scope=TenantContext.use(1,true)){
            assertThatThrownBy(()->download.downloadBook(foreign.getBookId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(()->reader.readTopics(foreign.getBookId(),List.of(topicId))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(()->metadata.patchBook(foreign.getBookId(),change("{\"bookType\":\"Physics\"}"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        try(var scope=TenantContext.use(2)){
            try(var pdf=Loader.loadPDF(download.downloadBook(foreign.getBookId()))){assertThat(pdf.getNumberOfPages()).isEqualTo(2);}
            assertThat(metadata.patchBook(foreign.getBookId(),change("{\"bookType\":\"Physics\"}")).bookType()).isEqualTo("Physics");
        }
        assertThat(Files.exists(storage.resolve("2").resolve("owned.pdf"))).isTrue();
    }

    @Test void metadataCannotBypassTenantOwnershipEvenForSuperUser() {
        BookDetails foreign;
        try(var scope=TenantContext.use(2)){foreign=book("Foreign title");book("Conflicting title");}
        try(var scope=TenantContext.use(1,true)){
            assertThatThrownBy(()->metadata.patchBook(foreign.getBookId(),change("{\"bookName\":\"Renamed\"}"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        try(var scope=TenantContext.use(2)){
            assertThatThrownBy(()->metadata.patchBook(foreign.getBookId(),change("{\"bookName\":\"Conflicting title\"}"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        }
    }

    @Test void superUserCannotReadOrMutateAnotherTenantsReceipt() {
        BookUploadStatus foreign;
        try(var scope=TenantContext.use(2)){foreign=receipt("Foreign receipt");}
        try(var scope=TenantContext.use(1,true)){
            assertThat(receipts.readCurrent(foreign.getBookUploadId())).isEmpty();
            assertThat(receipts.finishIfInProgress(foreign.getBookUploadId(),BookUploadStatus.IN_PROGRESS,BookUploadStatus.FAILED,"Spoofed")).isZero();
            assertThatThrownBy(()->new BookUploadStatusService(receipts).get(foreign.getBookUploadId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        try(var scope=TenantContext.use(2)){
            assertThat(receipts.readCurrent(foreign.getBookUploadId()).orElseThrow().getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
            assertThat(receipts.finishIfInProgress(foreign.getBookUploadId(),BookUploadStatus.IN_PROGRESS,BookUploadStatus.SUCCESS,"Done")).isEqualTo(1);
        }
    }

    @Test void newSuperUserBooksAndReceiptsStillBelongToTheActorAndCreationDuplicateChecksRemainTenantLocal() {
        String title="Shared assigned title "+UUID.randomUUID();
        try (var ignored=TenantContext.use(2)) { book(title); }
        try (var ignored=TenantContext.use(1,true)) {
            assertThat(books.existsByCanonicalBookName(title)).isFalse();
            BookDetails created=book(title);
            BookUploadStatus createdReceipt=receipt("New actor upload");
            assertThat(created.getTenantId()).isEqualTo(1L);
            assertThat(createdReceipt.getTenantId()).isEqualTo(1L);
            assertThat(books.existsByCanonicalBookName(title)).isTrue();
            assertThat(TenantContext.requireTenantId()).isEqualTo(1L);
            assertThat(TenantContext.isSuperUser()).isTrue();
        }
    }

    private Path pdf(String name) throws Exception {
        Path path=Files.createDirectories(storage.resolve(Long.toString(TenantContext.requireTenantId()))).resolve(name);
        try (var document=new PDDocument()) { document.addPage(new PDPage()); document.save(path.toFile()); }
        return path;
    }
    private BookUploadStatus receipt(String message) {
        BookUploadStatus result=new BookUploadStatus(); result.setStatus(BookUploadStatus.IN_PROGRESS); result.setMessage(message);
        entityManager.persist(result); entityManager.flush(); return result;
    }
    private static MetadataPatchRequest change(String json) {
        return JsonMapper.builder().build().readValue(json,MetadataPatchRequest.class);
    }
    private BookDetails book(String name) {
        AuthorDetails author=new AuthorDetails(); author.setAuthorName("Author");
        BookDetails result=new BookDetails(); result.setBookName(name); result.setExtractionId(UUID.randomUUID().toString());
        result.setAuthorDetails(author); result.setTotalPages(1);
        TopicDetails topic=new TopicDetails(); topic.setTopicName("Chapter"); topic.setStartPage(1); topic.setEndPage(1); result.addTopic(topic);
        entityManager.persist(result); entityManager.flush(); return result;
    }
}
