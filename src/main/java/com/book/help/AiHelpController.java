package com.book.help;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai-help")
public class AiHelpController {
    private final ChatService chats;
    public AiHelpController(ChatService chats) { this.chats = chats; }

    @GetMapping("/pages")
    public ChatService.Pages pages() { return chats.list(); }

    @PostMapping("/pages")
    public ResponseEntity<ChatPage> create() { return ResponseEntity.status(HttpStatus.CREATED).body(chats.create()); }

    public record Selection(long pageId) { }
    @PutMapping("/selection")
    public ChatPage select(@RequestBody Selection input) { return chats.select(input.pageId()); }

    @PatchMapping("/pages/{pageId}")
    public ChatPage rename(@PathVariable long pageId, @RequestBody Map<String,Object> input) {
        if (!input.keySet().stream().allMatch(key -> key.equals("name") || key.equals("description"))
                || !(input.get("name") instanceof String) || input.get("description") != null && !(input.get("description") instanceof String))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a chat name and optional text details only.");
        return chats.rename(pageId, (String) input.get("name"), (String) input.get("description"), input.containsKey("description"));
    }

    @GetMapping("/pages/{pageId}/messages")
    public ChatService.Messages messages(@PathVariable long pageId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return chats.messages(pageId, page, size);
    }

    @PostMapping(value = "/chat", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatService.Submission chat(@RequestParam long pageId, @RequestParam long revision,
            @RequestHeader(value = "X-Message-Id", required = false) String requestId, @RequestBody String messageText) {
        var saved = chats.send(pageId, revision, messageText, requestId);
        System.out.println(messageText);
        return saved;
    }

    @DeleteMapping("/pages/{pageId}/messages")
    public ChatPage clean(@PathVariable long pageId, @RequestParam long revision) { return chats.clean(pageId, revision); }

    @DeleteMapping("/pages/{pageId}")
    public ChatService.Deleted delete(@PathVariable long pageId, @RequestParam long revision) { return chats.delete(pageId, revision); }

    @GetMapping("/pages/{pageId}/pdf")
    public ResponseEntity<byte[]> export(@PathVariable long pageId) {
        var export = chats.export(pageId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(export.bytes());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason() == null ? "The chat request could not be completed." : failure.getReason()));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ProblemDetail> databaseFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The chat database operation did not complete. Reload the selected chat and retry; no success has been reported."));
    }
}
