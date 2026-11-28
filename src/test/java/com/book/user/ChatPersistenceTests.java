package com.book.user;

import com.book.help.*;
import com.book.tools.ServiceToolAccess;
import com.book.user.security.UserPrincipal;
import com.book.user.service.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatPersistenceTests {
    @TempDir Path temporary;
    JdbcTemplate jdbc; ChatService service; ServiceToolAccess access;
    UserPrincipal actor, first, second, foreignTenant;
    @BeforeEach void setup() throws Exception {
        jdbc = AuthTestDatabase.create(); migrate(jdbc); migrate(jdbc);
        var provisioning = new TenantProvisioningService(jdbc, Clock.systemUTC(), new com.book.storage.TenantStoragePaths(temporary.resolve("storage").toString()));
        var one = provisioning.create("INDIVIDUAL", "PASSWORD", "chat-one@example.com", "One", null, null, true);
        var two = provisioning.create("INDIVIDUAL", "PASSWORD", "chat-two@example.com", "Two", null, null, true);
        first = new UserPrincipal(one.userId(), one.tenantId(), one.membershipId(), 1, 1, 1, 1, "fixture");
        second = new UserPrincipal(two.userId(), one.tenantId(), two.membershipId(), 2, 1, 1, 1, "fixture");
        foreignTenant = new UserPrincipal(one.userId(), two.tenantId(), one.membershipId(), 3, 1, 1, 1, "fixture");
        actor = first; access = mock(ServiceToolAccess.class);
        when(access.require(anyString(), anyString())).thenAnswer(invocation -> actor);
        service = instance();
    }
    ChatService instance() { return new ChatService(jdbc, access, Clock.systemUTC(), new ChatPdfExporter(), new DataSourceTransactionManager(jdbc.getDataSource())); }
    static void migrate(JdbcTemplate jdbc) throws Exception {
        String sql = new ClassPathResource("db/manual/20261128_persistent_chats.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^SET time_zone[^;]*;", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", "").replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
    }
    @Test void namesAvoidConflictsAndPerUserSelectionsSurviveNewInstances() {
        ChatPage one = service.create(), two = service.create();
        assertThat(one.name()).isEqualTo("Chat 1"); assertThat(two.name()).isEqualTo("Chat 2");
        service.rename(one.pageId(), "Chat 3", null, false); assertThat(service.create().name()).isEqualTo("Chat 4");
        service.select(two.pageId());
        assertThat(instance().list().lastSelectedPageId()).isEqualTo(two.pageId());
        actor = second; assertThat(instance().list().pages()).isEmpty(); assertThat(instance().list().lastSelectedPageId()).isNull();
        assertThat(service.create().name()).isEqualTo("Chat 1");
    }
    @Test void concurrentCreationAndSendsUseUniqueNamesAndStableOrdering() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var creates = new ArrayList<java.util.concurrent.Future<ChatPage>>();
            for (int index = 0; index < 6; index++) creates.add(pool.submit(service::create));
            var pages = new ArrayList<ChatPage>(); for (var future : creates) pages.add(future.get());
            assertThat(pages.stream().map(ChatPage::name)).doesNotHaveDuplicates(); ChatPage page = pages.getFirst();
            var sends = new ArrayList<java.util.concurrent.Future<ChatService.Submission>>();
            for (int index = 0; index < 8; index++) { final int number = index; sends.add(pool.submit(() -> service.send(page.pageId(), 1, "Message " + number, UUID.randomUUID().toString()))); }
            for (var future : sends) future.get();
            assertThat(service.messages(page.pageId(), 0, 50).messages().stream().map(ChatMessage::messageOrder)).containsExactly(1L,2L,3L,4L,5L,6L,7L,8L);
        }
    }
    @Test void everyPrivateOperationChecksOwnerAndTenant() {
        ChatPage page = service.create(); service.send(page.pageId(), 1, "private", null);
        for (UserPrincipal unauthorized : List.of(second, foreignTenant)) {
            actor = unauthorized; assertThat(service.list().pages()).isEmpty();
            missing(() -> service.select(page.pageId())); missing(() -> service.rename(page.pageId(), "Stolen", null, false));
            missing(() -> service.messages(page.pageId(), 0, 50)); missing(() -> service.send(page.pageId(), 1, "forged", null));
            missing(() -> service.appendAssistantReply(page.pageId(), 1, "forged")); missing(() -> service.clean(page.pageId(), 1));
            missing(() -> service.delete(page.pageId(), 1)); missing(() -> service.export(page.pageId()));
        }
        actor = first; assertThat(service.messages(page.pageId(), 0, 50).messages()).hasSize(1);
    }
    @Test void separateUserAndActualAssistantMessagesAndNewestWindowsRemainPageScoped() {
        ChatPage one = service.create(), two = service.create();
        for (int index = 1; index <= 5; index++) service.send(one.pageId(), 1, "one-" + index, null);
        ChatMessage assistant = service.appendAssistantReply(one.pageId(), 1, "Actual integration reply");
        service.send(two.pageId(), 1, "other-chat", null);
        var newest = service.messages(one.pageId(), 0, 2);
        assertThat(newest.messages().stream().map(ChatMessage::content)).containsExactly("one-5", "Actual integration reply");
        assertThat(newest.totalElements()).isEqualTo(6); assertThat(newest.hasOlder()).isTrue();
        assertThat(service.messages(one.pageId(), 2, 2).messages().stream().map(ChatMessage::content)).containsExactly("one-1", "one-2");
        assertThat(assistant.senderRole()).isEqualTo("ASSISTANT");
        assertThat(service.messages(one.pageId(), 0, 50).messages().stream().map(ChatMessage::messageId)).doesNotHaveDuplicates();
    }
    @Test void retryIsIdempotentAndCleanRetainsPageButRejectsStaleSendsAndReplies() {
        ChatPage page = service.create(); String key = UUID.randomUUID().toString();
        var sent = service.send(page.pageId(), 1, "hi", key);
        assertThat(service.send(page.pageId(), 1, "hi", key).message().messageId()).isEqualTo(sent.message().messageId());
        conflict(() -> service.send(page.pageId(), 1, "different", key));
        ChatPage cleaned = service.clean(page.pageId(), 1);
        assertThat(cleaned.name()).isEqualTo(page.name()); assertThat(cleaned.pageId()).isEqualTo(page.pageId()); assertThat(cleaned.revision()).isEqualTo(2);
        assertThat(service.messages(page.pageId(), 0, 50).messages()).isEmpty();
        conflict(() -> service.send(page.pageId(), 1, "hi", key)); conflict(() -> service.appendAssistantReply(page.pageId(), 1, "late reply"));
        conflict(() -> service.export(page.pageId())); service.send(page.pageId(), 2, "new conversation", UUID.randomUUID().toString());
        assertThat(service.messages(page.pageId(), 0, 50).messages().getFirst().content()).isEqualTo("new conversation");
    }
    @Test void deletingPageCascadesOnlyItsMessagesAndUpdatesSelection() {
        ChatPage one = service.create(), two = service.create(); service.send(one.pageId(), 1, "keep", null); service.send(two.pageId(), 1, "remove", null);
        assertThat(service.delete(two.pageId(), 1).lastSelectedPageId()).isEqualTo(one.pageId()); missing(() -> service.messages(two.pageId(), 0, 50));
        assertThat(service.messages(one.pageId(), 0, 50).messages()).hasSize(1);
        assertThat(service.delete(one.pageId(), 1).lastSelectedPageId()).isNull(); assertThat(service.create().name()).isEqualTo("Chat 3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_messages", Long.class)).isZero();
    }
    @Test void cleanAndDeleteRollbackOnDatabaseErrors() {
        service.create(); ChatPage page = service.create(); var message = service.send(page.pageId(), 1, "Retain on rollback", null).message();
        jdbc.execute("CREATE TABLE chat_message_blocker(message_id BIGINT REFERENCES ai_chat_messages(message_id))"); jdbc.update("INSERT INTO chat_message_blocker VALUES(?)", message.messageId());
        assertThatThrownBy(() -> service.clean(page.pageId(), 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.messages(page.pageId(), 0, 50).revision()).isEqualTo(1); assertThat(service.messages(page.pageId(), 0, 50).messages()).hasSize(1);
        assertThatThrownBy(() -> service.delete(page.pageId(), 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.list().lastSelectedPageId()).isEqualTo(page.pageId()); assertThat(service.list().pages()).hasSize(2);
        assertThat(service.messages(page.pageId(), 0, 50).messages()).hasSize(1);
    }
    @Test void pdfUsesCurrentSafeNameAndFullSelectedConversationIncludingHindi() throws Exception {
        assertThat(ChatPdfExporter.filename("COM\u00B9")).isEqualTo("_COM\u00B9.pdf");
        ChatPage page = service.create(), other = service.create(); service.rename(page.pageId(), "Support / हिन्दी", null, false);
        service.send(page.pageId(), 1, "FIRST SELECTED MESSAGE\nनमस्ते भारत। कृपया सहायता करें।", null);
        for (int index = 0; index < 75; index++) service.send(page.pageId(), 1, "Message " + index + " - " + "Readable long conversation text. ".repeat(8), null);
        service.appendAssistantReply(page.pageId(), 1, "LAST ACTUAL ASSISTANT REPLY"); service.send(other.pageId(), 1, "SECRET OTHER CHAT", null);
        assertThat(service.messages(page.pageId(), 0, 1).messages()).hasSize(1); var exported = service.export(page.pageId());
        assertThat(exported.filename()).isEqualTo("Support _ हिन्दी.pdf");
        try (var document = Loader.loadPDF(exported.bytes())) {
            var stripper = new PDFTextStripper();stripper.setSuppressDuplicateOverlappingText(false);
            String text = stripper.getText(document);
            assertThat(text.split(java.util.regex.Pattern.quote("Page 1 of " + document.getNumberOfPages()),-1).length-1).isEqualTo(1);
            assertThat(text).contains("FIRST SELECTED MESSAGE", "LAST ACTUAL ASSISTANT REPLY", "Assistant", "You").doesNotContain("SECRET OTHER CHAT");
            assertThat(text.codePoints().anyMatch(character -> Character.UnicodeScript.of(character) == Character.UnicodeScript.DEVANAGARI)).isTrue();
            assertThat(document.getNumberOfPages()).isGreaterThan(2);
            Path preview = Path.of("target/chat-pdf-preview"); Files.createDirectories(preview); Files.write(preview.resolve("selected-chat.pdf"), exported.bytes());
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0, 110), "png", preview.resolve("selected-chat-page-1.png").toFile());
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(document.getNumberOfPages()-1, 110), "png", preview.resolve("selected-chat-last-page.png").toFile());
        }
    }
    @Test void invalidInputNeverChangesStoredMessages() {
        ChatPage page = service.create();
        for (String value : List.of("", " \n", "x".repeat(32001))) assertThatThrownBy(() -> service.send(page.pageId(), 1, value, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.rename(page.pageId(), " ", null, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.messages(page.pageId(), 0, 201)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.messages(page.pageId(), 0, 50).messages()).isEmpty();
    }
    private static void missing(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404)); }
    private static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409)); }
}
