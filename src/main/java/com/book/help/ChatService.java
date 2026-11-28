package com.book.help;

import com.book.tools.ServiceToolAccess;
import com.book.user.security.UserPrincipal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Private owner-and-tenant conversations. No provider or support workflow is executed here. */
@Service
public class ChatService {
    public record Pages(List<ChatPage> pages, Long lastSelectedPageId) { }
    public record Messages(long pageId, long revision, List<ChatMessage> messages, int number,
            int size, long totalElements, int totalPages, boolean hasOlder) { }
    public record Submission(long pageId, ChatMessage message, String acknowledgment,
            String assistantStatus, long revision) { }
    public record Deleted(Long lastSelectedPageId) { }
    public record Export(String filename, byte[] bytes) { }
    private record Preference(Long selected, long nextNumber) { }
    private static final RowMapper<ChatPage> PAGE = (rs, row) -> new ChatPage(rs.getLong("page_id"),
            rs.getString("name"), rs.getString("description"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getLong("revision"));
    private static final RowMapper<ChatMessage> MESSAGE = (rs, row) -> new ChatMessage(rs.getLong("message_id"),
            rs.getLong("page_id"), rs.getString("sender_role"), rs.getString("content"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("message_order"));
    private final JdbcTemplate jdbc;
    private final ServiceToolAccess access;
    private final Clock clock;
    private final ChatPdfExporter pdf;
    private final TransactionTemplate transaction;

    public ChatService(JdbcTemplate jdbc, ServiceToolAccess access, Clock clock,
            ChatPdfExporter pdf, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.access = access; this.clock = clock; this.pdf = pdf;
        this.transaction = new TransactionTemplate(manager);
    }

    public Pages list() {
        UserPrincipal actor = access.require("GET", "/api/ai-help/pages");
        return tx(ignored -> {
            var pages = jdbc.query("SELECT * FROM ai_chat_pages WHERE owner_user_id=? AND tenant_id=? ORDER BY updated_at DESC,page_id DESC", PAGE,
                    actor.userId(), actor.tenantId());
            var selected = jdbc.query("SELECT selected_page_id FROM ai_chat_preferences WHERE owner_user_id=? AND tenant_id=?",
                    (rs, row) -> rs.getObject(1, Long.class), actor.userId(), actor.tenantId());
            Long id = selected.isEmpty() ? null : selected.getFirst();
            boolean accessible = id != null && pages.stream().anyMatch(page -> page.pageId() == id);
            return new Pages(pages, accessible ? id : null);
        });
    }

    public ChatPage create() {
        UserPrincipal actor = access.require("POST", "/api/ai-help/pages");
        return tx(ignored -> {
            Preference preference = lockPreference(actor);
            long number = preference.nextNumber();
            String name;
            do {
                if (number == Long.MAX_VALUE) throw invalid("No further default chat names can be allocated.");
                name = "Chat " + number++;
            } while (jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_pages WHERE owner_user_id=? AND tenant_id=? AND name=?",
                    Long.class, actor.userId(), actor.tenantId(), name) != 0);
            Timestamp now = now();
            long id = insert("INSERT INTO ai_chat_pages(owner_user_id,tenant_id,name,description,created_at,updated_at,revision,next_message_order) VALUES(?,?,?,NULL,?,?,1,0)",
                    actor.userId(), actor.tenantId(), name, now, now);
            jdbc.update("UPDATE ai_chat_preferences SET selected_page_id=?,next_default_number=? WHERE owner_user_id=? AND tenant_id=?",
                    id, number, actor.userId(), actor.tenantId());
            return page(actor, id, false);
        });
    }

    public ChatPage select(long pageId) {
        UserPrincipal actor = access.require("PUT", "/api/ai-help/selection");
        return tx(ignored -> {
            lockPreference(actor);
            ChatPage page = page(actor, pageId, true);
            jdbc.update("UPDATE ai_chat_preferences SET selected_page_id=? WHERE owner_user_id=? AND tenant_id=?",
                    pageId, actor.userId(), actor.tenantId());
            return page;
        });
    }

    public ChatPage rename(long pageId, String name, String description, boolean updateDescription) {
        UserPrincipal actor = access.require("PATCH", "/api/ai-help/pages/" + pageId);
        String cleanName = validateName(name);
        if (description != null && description.length() > 4000) throw invalid("Chat details cannot exceed 4000 characters.");
        return tx(ignored -> {
            lockPreference(actor);
            ChatPage current = page(actor, pageId, true);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_pages WHERE owner_user_id=? AND tenant_id=? AND name=? AND page_id<>?",
                    Long.class, actor.userId(), actor.tenantId(), cleanName, pageId) > 0)
                throw conflict("Another chat already has that name.");
            jdbc.update("UPDATE ai_chat_pages SET name=?,description=?,updated_at=? WHERE page_id=?",
                    cleanName, updateDescription ? description : current.description(), now(), pageId);
            return page(actor, pageId, false);
        });
    }

    /** Page zero is the newest window; each window is returned in ascending conversation order. */
    public Messages messages(long pageId, int page, int size) {
        UserPrincipal actor = access.require("GET", "/api/ai-help/pages/" + pageId + "/messages");
        if (page < 0 || size < 1 || size > 200 || (long) page * size > Integer.MAX_VALUE)
            throw invalid("Choose a valid message page and size (1 to 200).");
        return tx(ignored -> {
            // Locking prevents a clean/send between the epoch, count and selected message window.
            ChatPage selected = page(actor, pageId, true);
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_messages WHERE page_id=?", Long.class, pageId);
            var rows = jdbc.query("SELECT * FROM ai_chat_messages WHERE page_id=? ORDER BY message_order DESC LIMIT ? OFFSET ?",
                    MESSAGE, pageId, size, (long) page * size).reversed();
            int totalPages = (int) Math.ceil((double) count / size);
            return new Messages(pageId, selected.revision(), List.copyOf(rows), page, size, count, totalPages, page + 1 < totalPages);
        });
    }

    public Submission send(long pageId, long revision, String messageText, String requestId) {
        UserPrincipal actor = access.require("POST", "/api/ai-help/chat");
        validateMessage(messageText);
        String key = requestKey(requestId);
        return tx(ignored -> {
            lockPreference(actor);
            ChatPage selected = page(actor, pageId, true);
            requireRevision(selected, revision);
            if (key != null) {
                var existing = jdbc.query("SELECT * FROM ai_chat_messages WHERE page_id=? AND request_id=?", MESSAGE, pageId, key);
                if (!existing.isEmpty()) {
                    if (!existing.getFirst().content().equals(messageText)) throw conflict("This message retry ID was already used for different text.");
                    return submission(selected, existing.getFirst());
                }
            }
            ChatMessage message = append(pageId, "USER", messageText, key);
            return submission(selected, message);
        });
    }

    /** Future integration hook: save only an actual reply, in the still-current conversation epoch. */
    public ChatMessage appendAssistantReply(long pageId, long expectedRevision, String reply) {
        UserPrincipal actor = access.require("POST", "/api/ai-help/chat");
        validateMessage(reply);
        return tx(ignored -> {
            lockPreference(actor);
            requireRevision(page(actor, pageId, true), expectedRevision);
            return append(pageId, "ASSISTANT", reply, null);
        });
    }

    public ChatPage clean(long pageId, long revision) {
        UserPrincipal actor = access.require("DELETE", "/api/ai-help/pages/" + pageId + "/messages");
        return tx(ignored -> {
            lockPreference(actor);
            requireRevision(page(actor, pageId, true), revision);
            jdbc.update("DELETE FROM ai_chat_messages WHERE page_id=?", pageId);
            jdbc.update("UPDATE ai_chat_pages SET revision=revision+1,updated_at=? WHERE page_id=?", now(), pageId);
            return page(actor, pageId, false);
        });
    }

    public Deleted delete(long pageId, long revision) {
        UserPrincipal actor = access.require("DELETE", "/api/ai-help/pages/" + pageId);
        return tx(ignored -> {
            Preference preference = lockPreference(actor);
            requireRevision(page(actor, pageId, true), revision);
            Long selected = preference.selected();
            if (selected != null && selected == pageId) {
                var remaining = jdbc.query("SELECT page_id FROM ai_chat_pages WHERE owner_user_id=? AND tenant_id=? AND page_id<>? ORDER BY updated_at DESC,page_id DESC LIMIT 1",
                        (rs, row) -> rs.getLong(1), actor.userId(), actor.tenantId(), pageId);
                selected = remaining.isEmpty() ? null : remaining.getFirst();
                jdbc.update("UPDATE ai_chat_preferences SET selected_page_id=? WHERE owner_user_id=? AND tenant_id=?", selected, actor.userId(), actor.tenantId());
            }
            // The foreign key cascades all child messages in this same transaction.
            if (jdbc.update("DELETE FROM ai_chat_pages WHERE page_id=? AND owner_user_id=? AND tenant_id=?", pageId, actor.userId(), actor.tenantId()) != 1)
                throw missing();
            return new Deleted(selected);
        });
    }

    public Export export(long pageId) {
        UserPrincipal actor = access.require("GET", "/api/ai-help/pages/" + pageId + "/pdf");
        return tx(ignored -> {
            // A single page lock freezes this conversation for the full export, including older windows.
            ChatPage selected = page(actor, pageId, true);
            var messages = jdbc.query("SELECT * FROM ai_chat_messages WHERE page_id=? ORDER BY message_order", MESSAGE, pageId);
            if (messages.isEmpty()) throw conflict("This chat is empty. There are no messages to download.");
            return new Export(ChatPdfExporter.filename(selected.name()), pdf.render(selected, messages));
        });
    }

    private Submission submission(ChatPage page, ChatMessage message) {
        return new Submission(page.pageId(), message, "Message saved. AI generation is not connected.", "NOT_CONFIGURED", page.revision());
    }
    private ChatMessage append(long pageId, String role, String text, String requestId) {
        long order = jdbc.queryForObject("SELECT next_message_order FROM ai_chat_pages WHERE page_id=?", Long.class, pageId) + 1;
        Timestamp timestamp = now();
        long id = insert("INSERT INTO ai_chat_messages(page_id,sender_role,content,created_at,message_order,request_id) VALUES(?,?,?,?,?,?)",
                pageId, role, text, timestamp, order, requestId);
        jdbc.update("UPDATE ai_chat_pages SET next_message_order=?,updated_at=? WHERE page_id=?", order, timestamp, pageId);
        return new ChatMessage(id, pageId, role, text, timestamp.toInstant(), order);
    }
    private Preference lockPreference(UserPrincipal actor) {
        // The authoritative user row serializes first preference creation, including concurrent requests.
        if (jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", Long.class, actor.userId()).size() != 1) throw missing();
        var rows = jdbc.query("SELECT selected_page_id,next_default_number FROM ai_chat_preferences WHERE owner_user_id=? AND tenant_id=? FOR UPDATE",
                (rs, row) -> new Preference(rs.getObject(1, Long.class), rs.getLong(2)), actor.userId(), actor.tenantId());
        if (!rows.isEmpty()) return rows.getFirst();
        jdbc.update("INSERT INTO ai_chat_preferences(owner_user_id,tenant_id,selected_page_id,next_default_number) VALUES(?,?,NULL,1)", actor.userId(), actor.tenantId());
        return new Preference(null, 1);
    }
    private ChatPage page(UserPrincipal actor, long id, boolean lock) {
        if (id <= 0) throw invalid("A positive chat page ID is required.");
        var rows = jdbc.query("SELECT * FROM ai_chat_pages WHERE page_id=? AND owner_user_id=? AND tenant_id=?" + (lock ? " FOR UPDATE" : ""), PAGE,
                id, actor.userId(), actor.tenantId());
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }
    private <T> T tx(Function<Void,T> operation) { return transaction.execute(status -> operation.apply(null)); }
    private long insert(String sql, Object... arguments) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
    private Timestamp now() { return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)); }
    private static void requireRevision(ChatPage page, long revision) {
        if (revision != page.revision()) throw conflict("This chat changed after a clean operation. Reload it before sending or removing messages.");
    }
    private static String requestKey(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException failure) { throw invalid("X-Message-Id must be a UUID."); }
    }
    private static void validateMessage(String text) {
        if (text == null || text.isBlank()) throw invalid("A non-blank message is required.");
        if (text.length() > 32000) throw invalid("A message cannot exceed 32000 characters.");
        if (text.codePoints().anyMatch(character -> Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t'))
            throw invalid("A message contains unsupported control characters.");
    }
    private static String validateName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 150 || name.codePoints().anyMatch(Character::isISOControl))
            throw invalid("Enter a non-blank chat name of at most 150 characters.");
        return name.strip();
    }
    private static ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "The chat page was not found."); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
