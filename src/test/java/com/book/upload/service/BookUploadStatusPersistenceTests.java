package com.book.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import com.book.entity.BookUploadStatus;
import com.book.repository.BookUploadStatusRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Uses separate real transactions so status receipts remain visible after the import rolls back. */
@DataJpaTest(showSql = false, properties = { "spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
@Import(BookUploadStatusService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookUploadStatusPersistenceTests extends com.book.user.TenantAwareTest {
    @Autowired BookUploadStatusService service;
    @Autowired BookUploadStatusRepository statuses;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void createsCommittedReceiptWithExactlyTheRequestedFieldsAndMessage() {
        BookUploadStatus receipt = service.create("original book.pdf");

        assertThat(receipt.getBookUploadId()).isPositive();
        assertThat(receipt.getTenantId()).isEqualTo(1L);
        assertThat(receipt.getStatus()).isEqualTo("inprogress");
        assertThat(receipt.getMessage()).isEqualTo("original book.pdf upload service is uploading book topic by topic."
                + " please check final upload status after some time.");
        BookUploadStatus read = service.get(receipt.getBookUploadId());
        assertThat(read).isNotSameAs(receipt);
        assertThat(read.getMessage()).isEqualTo(receipt.getMessage());
        assertThat(read.getTenantId()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select tenant_id from pdf_file_status where book_upload_id = ?",
                Long.class, receipt.getBookUploadId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = 'PDF_FILE_STATUS'
                """, Integer.class)).isEqualTo(10);

        JsonMapper mapper = JsonMapper.builder().build();
        Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(receipt), Map.class);
        assertThat(json).hasSize(10);
        assertThat(json.keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("book_upload_id", "status", "message","fileName","bookName","uploadedBy","createdAt","updatedAt","state","failureReason");
        assertThat(json.get("uploadedBy")).isEqualTo(1);
        assertThat(json.get("state")).isEqualTo("PENDING");
        assertThat(json.get("createdAt").toString()).endsWith("Z");
        assertThat(((Number) json.get("book_upload_id")).longValue()).isEqualTo(receipt.getBookUploadId());
    }

    @Test
    void historyAndIndividualStatusRequireBothTenantAndUploaderIncludingSuperUsers() {
        var own=service.create("owned.pdf");
        assertThat(own.getUploadedBy()).isEqualTo(1L);
        assertThat(service.history(0,20).getContent()).extracting(BookUploadStatus::getBookUploadId).contains(own.getBookUploadId());
        try(var peer=com.book.user.security.TenantContext.use(1L,2L,false)) {
            assertThat(service.history(0,20).getContent()).isEmpty();
            assertThatThrownBy(()->service.get(own.getBookUploadId())).hasMessageContaining("404");
            assertThatThrownBy(()->service.fail(own.getBookUploadId(),new IOException("forged"))).hasMessageContaining("404");
        }
        try(var foreign=com.book.user.security.TenantContext.use(2L,1L,true)) {
            assertThat(service.history(0,20).getContent()).isEmpty();
            assertThatThrownBy(()->service.get(own.getBookUploadId())).hasMessageContaining("404");
        }
        assertThat(service.get(own.getBookUploadId()).getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
        assertThatThrownBy(()->service.history(-1,20)).hasMessageContaining("400");
        assertThatThrownBy(()->service.history(0,101)).hasMessageContaining("400");
    }
    @Test
    void legacyUnownedReceiptsAreNotAdoptedAndPendingProcessingTerminalTimesAreServerUtc() {
        var legacy=new BookUploadStatus();legacy.setUploadedBy(null);legacy.setStatus(BookUploadStatus.IN_PROGRESS);legacy.setMessage("legacy");
        legacy=statuses.saveAndFlush(legacy);
        Long legacyId=legacy.getBookUploadId();
        assertThatThrownBy(()->service.get(legacyId)).hasMessageContaining("404");
        var started=java.time.Instant.now();var receipt=service.create("states.pdf");
        assertThat(receipt.getCreatedAt()).isAfterOrEqualTo(started.minusSeconds(1));
        assertThat(receipt.getCreatedAt()).isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(receipt.getState()).isEqualTo("PENDING");
        service.processing(receipt.getBookUploadId());
        assertThat(service.get(receipt.getBookUploadId()).getState()).isEqualTo("PROCESSING");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->service.succeed(receipt.getBookUploadId(),"Completed name"));
        var completed=service.get(receipt.getBookUploadId());
        assertThat(completed.getState()).isEqualTo("COMPLETED");assertThat(completed.getBookName()).isEqualTo("Completed name");
        assertThat(completed.getUpdatedAt()).isAfterOrEqualTo(completed.getCreatedAt());
        assertThat(service.history(0,100).getContent()).extracting(BookUploadStatus::getBookUploadId).doesNotContain(legacyId);
        assertThat(receipt.getState()).isEqualTo("PENDING");
    }
    @Test
    void receiptCommitSurvivesTheCallerTransactionRollingBack() {
        AtomicReference<BookUploadStatus> receipt = new AtomicReference<>();
        TransactionTemplate caller = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> caller.executeWithoutResult(status -> {
            receipt.set(service.create("background.pdf"));
            throw new IllegalStateException("Simulated caller failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.get(receipt.get().getBookUploadId()).getStatus()).isEqualTo("inprogress");
    }

    @Test
    void successCommitsWithTheUploadAndDoesNotMutateTheInitialAcknowledgement() {
        BookUploadStatus receipt = service.create("book.pdf");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.succeed(receipt.getBookUploadId(), "Book Name"));

        BookUploadStatus completed = service.get(receipt.getBookUploadId());
        assertThat(completed.getStatus()).isEqualTo("Success");
        assertThat(completed.getMessage()).isEqualTo("uploaded Book Name successfully.");
        assertThat(receipt.getStatus()).isEqualTo("inprogress");
        assertThat(receipt.getMessage()).startsWith("book.pdf upload service");

        service.fail(receipt.getBookUploadId(), new IOException("Late callback must not erase success"));
        assertThat(service.get(receipt.getBookUploadId()).getStatus()).isEqualTo("Success");
    }

    @Test
    void rolledBackSuccessLeavesReceiptThenIndependentFailurePersists() {
        BookUploadStatus receipt = service.create("book.pdf");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.succeed(receipt.getBookUploadId(), "Book Name");
            assertThat(jdbc.queryForObject("select status from pdf_file_status where book_upload_id = ?",
                    String.class, receipt.getBookUploadId())).isEqualTo("Success");
            status.setRollbackOnly();
        });

        assertThat(service.get(receipt.getBookUploadId()).getStatus()).isEqualTo("inprogress");
        service.fail(receipt.getBookUploadId(), new IOException("Could not save the topic PDF"));
        BookUploadStatus failed = service.get(receipt.getBookUploadId());
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getMessage()).isEqualTo("Could not save the topic PDF");
    }

    @Test
    void failureStatusAlsoSurvivesAnUnrelatedCallerRollback() {
        BookUploadStatus receipt = service.create("book.pdf");
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> caller.executeWithoutResult(status -> {
            service.fail(receipt.getBookUploadId(), new IllegalStateException("Background task rejected"));
            throw new IllegalStateException("Caller rolls back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.get(receipt.getBookUploadId()).getStatus()).isEqualTo("failed");
    }

    @Test
    void unwrapsAsyncErrorsAndUsesUsefulHttpFailureReason() {
        BookUploadStatus receipt = service.create("book.pdf");
        service.fail(receipt.getBookUploadId(), new CompletionException(new ResponseStatusException(
                HttpStatus.CONFLICT, "A book named 'Book' already exists.")));
        assertThat(service.get(receipt.getBookUploadId()).getMessage()).isEqualTo("A book named 'Book' already exists.");
    }

    @Test
    void failedStateCannotBePromotedOrReplacedByALaterFailure() {
        BookUploadStatus receipt = service.create("book.pdf");
        service.fail(receipt.getBookUploadId(), new IOException("First failure"));
        service.fail(receipt.getBookUploadId(), new IOException("Second failure"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.succeed(receipt.getBookUploadId(), "Book Name")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already failed");
        BookUploadStatus finalState = service.get(receipt.getBookUploadId());
        assertThat(finalState.getStatus()).isEqualTo("failed");
        assertThat(finalState.getMessage()).isEqualTo("First failure");
    }

    @Test
    void successRequiresTheOwningUploadTransaction() {
        BookUploadStatus receipt = service.create("book.pdf");
        assertThatThrownBy(() -> service.succeed(receipt.getBookUploadId(), "Book Name"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(service.get(receipt.getBookUploadId()).getStatus()).isEqualTo("inprogress");
    }

    @Test
    void unknownUploadIdsReturnNotFoundWithoutCreatingAStatus() {
        long before = statuses.count();
        assertThatThrownBy(() -> service.get(Long.MAX_VALUE)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.fail(Long.MAX_VALUE, new IOException("failure")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(statuses.count()).isEqualTo(before);
    }

    @Test
    void readsFreshFailureEvenWhenTheHttpPersistenceContextStillCachesTheInitialReceipt() {
        // Simulates OpenEntityManagerInView: consecutive service transactions reuse one request EntityManager.
        EntityManager requestEntityManager = entityManagerFactory.createEntityManager();
        assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            BookUploadStatus receipt = service.create("queue-rejected.pdf");
            BookUploadStatus cached = requestEntityManager.find(BookUploadStatus.class, receipt.getBookUploadId());
            assertThat(requestEntityManager.contains(cached)).isTrue();
            assertThat(cached.getStatus()).isEqualTo("inprogress");

            service.fail(receipt.getBookUploadId(), new RejectedExecutionException("Upload queue is full"));

            assertThat(cached.getStatus()).isEqualTo("inprogress"); // The guarded bulk update does not rewrite managed values.
            BookUploadStatus actual = service.get(receipt.getBookUploadId());
            assertThat(actual.getStatus()).isEqualTo("failed");
            assertThat(actual.getMessage()).isEqualTo("Upload queue is full");
            assertThat(receipt.getStatus()).isEqualTo("inprogress");
        }
        finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
        }
    }
}
