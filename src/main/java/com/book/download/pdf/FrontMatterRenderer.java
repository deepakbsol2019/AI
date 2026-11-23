package com.book.download.pdf;

import com.book.download.dto.DownloadBookModel;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/** Local XHTML rendering only; each request owns its context, renderer and PDF output. */
@Component
public class FrontMatterRenderer {
    private static final String REGULAR = "/fonts/download/NotoSans-Regular.ttf";
    private static final String BOLD = "/fonts/download/NotoSans-Bold.ttf";
    private final ITemplateEngine templateEngine;
    private final Font regularFont;
    private final Font boldFont;

    public FrontMatterRenderer(@Qualifier("downloadTemplateEngine") ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        regularFont = loadCoverageFont(REGULAR);
        boldFont = loadCoverageFont(BOLD);
    }

    public byte[] render(DownloadBookModel model) {
        String bookName = normalized(model.book() == null ? null : model.book().name());
        String authorName = normalized(model.author() == null ? null : model.author().name());
        List<String> topicNames = model.selectedTopics().stream().map(topic -> normalized(topic.name())).toList();
        verifySupportedText(bookName, boldFont);
        verifySupportedText(authorName, regularFont);
        topicNames.forEach(name -> verifySupportedText(name, regularFont));

        Context context = new Context(Locale.ROOT);
        context.setVariable("bookName", bookName);
        context.setVariable("authorName", authorName);
        context.setVariable("topicNames", topicNames);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            String html = templateEngine.process("front-matter", context);
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.useFont(() -> fontStream(REGULAR), "Download Noto Sans", 400, FontStyle.NORMAL, true);
            builder.useFont(() -> fontStream(BOLD), "Download Noto Sans", 700, FontStyle.NORMAL, true);
            // Fonts are supplied directly above. This template needs no network or filesystem resources.
            builder.useExternalResourceAccessControl((uri, type) -> false,
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl((uri, type) -> false,
                    ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not render the book cover and contents PDF", exception);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }

    private static InputStream fontStream(String resource) {
        InputStream stream = FrontMatterRenderer.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled download font: " + resource);
        }
        return stream;
    }

    private static Font loadCoverageFont(String resource) {
        try (InputStream stream = fontStream(resource)) {
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (IOException | FontFormatException exception) {
            throw new IllegalStateException("Cannot load bundled download font: " + resource, exception);
        }
    }

    /** Fail visibly rather than silently replacing unsupported characters with missing-glyph boxes. */
    private static void verifySupportedText(String value, Font font) {
        if (value == null) {
            return;
        }
        value.codePoints().forEach(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            boolean supportedScript = script == Character.UnicodeScript.LATIN
                    || script == Character.UnicodeScript.GREEK || script == Character.UnicodeScript.CYRILLIC
                    || script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED;
            if ((!Character.isWhitespace(codePoint) && !font.canDisplay(codePoint)) || !supportedScript
                    || (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Download front matter cannot render metadata character U+"
                                + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT)
                                + "; bundled fonts support Latin, Greek and Cyrillic text");
            }
        });
    }
}
