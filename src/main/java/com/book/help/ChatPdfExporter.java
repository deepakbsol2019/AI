package com.book.help;

import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.BreakIterator;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Request-local PDFBox output with embedded Latin and Devanagari fonts and real page breaks. */
@Component
public class ChatPdfExporter {
    private static final String LATIN = "/fonts/download/NotoSans-Regular.ttf";
    private static final String BOLD = "/fonts/download/NotoSans-Bold.ttf";
    private static final String DEVANAGARI = "/fonts/chat/NotoSansDevanagari-Regular.ttf";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private final Font latinCoverage = coverage(LATIN);
    private final Font devanagariCoverage = coverage(DEVANAGARI);

    public byte[] render(ChatPage page, List<ChatMessage> messages) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
                var latinInput = font(LATIN); var boldInput = font(BOLD); var devanagariInput = font(DEVANAGARI)) {
            var latin = PDType0Font.load(document, latinInput);
            var bold = PDType0Font.load(document, boldInput);
            var devanagari = PDType0Font.load(document, devanagariInput);
            try (var writer = new Writer(document, latin, bold, devanagari)) {
                writer.paragraph(page.name(), 17, true, 10);
                writer.paragraph("Conversation export - " + messages.size() + " messages", 9, false, 16);
                for (ChatMessage message : messages) {
                    writer.reserve(56); // Keep each sender label with at least the opening body lines.
                    writer.paragraph((message.senderRole().equals("ASSISTANT") ? "Assistant" : "You") + " - " + TIME.format(message.createdAt()), 9, true, 4);
                    writer.paragraph(message.content(), 11, false, 14);
                }
            }
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                try (var footer = new PDPageContentStream(document, document.getPage(index), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.beginText(); footer.setFont(latin, 8); footer.newLineAtOffset(48, 24);
                    footer.showText("Page " + (index + 1) + " of " + document.getNumberOfPages()); footer.endText();
                }
            }
            document.getDocumentInformation().setTitle(page.name());
            document.save(output);
            return output.toByteArray();
        } catch (ResponseStatusException failure) { throw failure; }
        catch (IOException | IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The selected chat PDF could not be generated.", failure);
        }
    }

    public static String filename(String name) {
        return com.book.storage.DocumentFileStore.safeFilename(name) + ".pdf";
    }

    private final class Writer implements AutoCloseable {
        private static final float MARGIN = 48;
        private static final float BOTTOM = 46;
        private final PDDocument document;
        private final PDType0Font latin, bold, devanagari;
        private PDPageContentStream stream;
        private float y;
        Writer(PDDocument document, PDType0Font latin, PDType0Font bold, PDType0Font devanagari) throws IOException {
            this.document = document; this.latin = latin; this.bold = bold; this.devanagari = devanagari;
            newPage();
        }
        private void newPage() throws IOException {
            if (stream != null) stream.close();
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - 52;
        }
        private void reserve(float height) throws IOException { if (y - height < BOTTOM) newPage(); }
        void paragraph(String text, float size, boolean strong, float spacing) throws IOException {
            for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1)) {
                for (String line : wrap(paragraph, size, strong)) {
                    if (y - size * 1.55f < BOTTOM) newPage();
                    stream.beginText(); stream.newLineAtOffset(MARGIN, y);
                    for (Run run : runs(line, strong)) { stream.setFont(run.font(), size); stream.showText(run.text()); }
                    stream.endText(); y -= size * 1.55f;
                }
            }
            y -= spacing;
        }
        private List<String> wrap(String text, float size, boolean strong) throws IOException {
            if (text.isEmpty()) return List.of("");
            float available = PDRectangle.A4.getWidth() - MARGIN * 2;
            var result = new ArrayList<String>();
            var line = new StringBuilder();
            BreakIterator words = BreakIterator.getLineInstance(Locale.ROOT); words.setText(text);
            int start = words.first();
            for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
                String word = text.substring(start, end);
                if (!line.isEmpty() && width(line + word, size, strong) > available) { result.add(line.toString().stripTrailing()); line.setLength(0); }
                if (width(word, size, strong) <= available) { line.append(word); continue; }
                BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT); characters.setText(word);
                int clusterStart = characters.first();
                for (int clusterEnd = characters.next(); clusterEnd != BreakIterator.DONE; clusterStart = clusterEnd, clusterEnd = characters.next()) {
                    String cluster = word.substring(clusterStart, clusterEnd);
                    if (!line.isEmpty() && width(line + cluster, size, strong) > available) { result.add(line.toString()); line.setLength(0); }
                    line.append(cluster);
                }
            }
            if (!line.isEmpty()) result.add(line.toString().stripTrailing());
            return result;
        }
        private float width(String text, float size, boolean strong) throws IOException {
            float width = 0;
            for (Run run : runs(text, strong)) width += run.font().getStringWidth(run.text()) * size / 1000;
            return width;
        }
        private List<Run> runs(String text, boolean strong) {
            var result = new ArrayList<Run>();
            PDType0Font current = null; var value = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                int character = text.codePointAt(offset); offset += Character.charCount(character);
                PDType0Font selected;
                if (Character.UnicodeScript.of(character) == Character.UnicodeScript.DEVANAGARI && devanagariCoverage.canDisplay(character)) selected = devanagari;
                else if (latinCoverage.canDisplay(character)) selected = strong ? bold : latin;
                else if (devanagariCoverage.canDisplay(character)) selected = devanagari;
                else throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The bundled chat PDF fonts do not support character U+" + Integer.toHexString(character).toUpperCase(Locale.ROOT) + ". The conversation remains saved.");
                if (current != selected && !value.isEmpty()) { result.add(new Run(current, value.toString())); value.setLength(0); }
                current = selected; value.appendCodePoint(character);
            }
            if (!value.isEmpty()) result.add(new Run(current, value.toString()));
            return result;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
    private record Run(PDType0Font font, String text) { }
    private static InputStream font(String resource) {
        var input = ChatPdfExporter.class.getResourceAsStream(resource);
        if (input == null) throw new IllegalStateException("Missing bundled chat PDF font: " + resource);
        return input;
    }
    private static Font coverage(String resource) {
        try (var input = font(resource)) { return Font.createFont(Font.TRUETYPE_FONT, input); }
        catch (Exception failure) { throw new IllegalStateException("Could not load bundled chat PDF font: " + resource, failure); }
    }
}
