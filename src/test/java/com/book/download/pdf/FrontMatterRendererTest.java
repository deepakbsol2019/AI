package com.book.download.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.book.download.config.DownloadTemplateConfiguration;
import com.book.download.dto.DownloadBookModel;
import com.book.download.dto.DownloadBookModel.Author;
import com.book.download.dto.DownloadBookModel.Book;
import com.book.download.dto.DownloadBookModel.Topic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FrontMatterRendererTest {
    private final FrontMatterRenderer renderer = new FrontMatterRenderer(
            new DownloadTemplateConfiguration().downloadTemplateEngine());

    @Test
    void oneCoverAndContentsKeepOnlySelectedNamesInInputOrder() throws Exception {
        byte[] pdf = renderer.render(model("Algorithms", new Author("Example Author"),
                List.of(new Topic(102L, "Graphs"), new Topic(100L, "Searching"), new Topic(101L, "Sorting"))));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(pageText(document, 1)).isEqualTo("Algorithms Example Author");
            assertThat(pageText(document, 2)).isEqualTo("Contents Graphs Searching Sorting");
            assertThat(document.getPage(1).getAnnotations()).isEmpty();
        }
    }

    @Test
    void absentOrBlankAuthorDoesNotPrintPlaceholderOrDanglingLabel() throws Exception {
        for (Author author : new Author[] {null, new Author(null), new Author("  ")}) {
            byte[] pdf = renderer.render(model("Algorithms", author, List.of(new Topic(100L, "Searching"))));
            try (PDDocument document = Loader.loadPDF(pdf)) {
                assertThat(document.getNumberOfPages()).isEqualTo(2);
                assertThat(pageText(document, 1)).isEqualTo("Algorithms");
                assertThat(pageText(document, 2)).isEqualTo("Contents Searching");
            }
        }
    }

    @Test
    void namesAreEscapedInsteadOfBeingTreatedAsMarkupOrExternalResources() throws Exception {
        String title = "Java <img src=\"https://example.invalid/book\" /> & XML";
        String name = "Graphs <advanced> & \"trees\"";
        byte[] pdf = renderer.render(model(title, new Author("A & B"), List.of(new Topic(100L, name))));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            // Line wrapping may split an uninterrupted URI; every literal character must survive.
            assertThat(pageText(document, 1).replace(" ", "")).isEqualTo((title + " A & B").replace(" ", ""));
            assertThat(pageText(document, 2)).isEqualTo("Contents " + name);
            assertThat(document.getPage(0).getResources().getXObjectNames()).isEmpty();
        }
    }

    @Test
    void longListAndTitleWrapAcrossContentsPagesWithoutLosingNamesAndWriteVisualPreview() throws Exception {
        String title = "Algorithmes et structures de données — Étude pratique des systèmes, "
                + "de la recherche, des graphes et des réseaux pour les développeurs et les étudiants : "
                + "Αλγόριθμοι, Основы программирования, méthodes et applications modernes";
        List<Topic> topics = IntStream.rangeClosed(1, 70)
                .mapToObj(index -> new Topic((long) index,
                        String.format("Topic %03d — Étude des données, Αλγόριθμοι and Основы: "
                                + "a practical exploration of searching, sorting and connected structures", index)))
                .toList();
        byte[] pdf = renderer.render(model(title, new Author("Zoë García & François Müller"), topics));
        Path preview = Path.of("target", "download-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("front-matter.pdf"), pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(3);
            assertTextInsidePageMargins(document);
            assertThat(pageText(document, 1)).isEqualTo(title + " Zoë García & François Müller");
            String contents = IntStream.rangeClosed(2, document.getNumberOfPages())
                    .mapToObj(page -> uncheckedPageText(document, page)).reduce("", (left, right) -> left + " " + right);
            int previous = -1;
            for (Topic topic : topics) {
                int position = contents.indexOf(topic.name());
                assertThat(position).as(topic.name()).isGreaterThan(previous);
                previous = position;
            }
            assertThat(contents.strip()).startsWith("Contents ");
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            List<String> pageNames = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                assertThat(pageText(document, index + 1)).as("Page %s must not be blank", index + 1).isNotBlank();
                String pageName = "front-matter-page-" + (index + 1) + ".png";
                ImageIO.write(pdfRenderer.renderImageWithDPI(index, 95), "PNG", preview.resolve(pageName).toFile());
                pageNames.add(pageName);
            }
            Files.writeString(preview.resolve("pages.txt"), String.join(System.lineSeparator(), pageNames));
        }
    }

    @Test
    void maximumLengthUnbrokenMetadataFitsOneCoverAndWrapsInsideMargins() throws Exception {
        String title = "W".repeat(255);
        String author = "M".repeat(200);
        String topic = "W".repeat(255);
        byte[] pdf = renderer.render(model(title, new Author(author), List.of(new Topic(1L, topic))));
        Path preview = Path.of("target", "download-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("unbroken-metadata.pdf"), pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertTextInsidePageMargins(document);
            assertThat(pageText(document, 1).replace(" ", "")).isEqualTo(title + author);
            assertThat(pageText(document, 2).replace(" ", "")).isEqualTo("Contents" + topic);
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                ImageIO.write(pdfRenderer.renderImageWithDPI(index, 95), "PNG",
                        preview.resolve("unbroken-metadata-page-" + (index + 1) + ".png").toFile());
            }
        }
    }

    @Test
    void unsupportedFontCharactersFailClearlyInsteadOfProducingMissingGlyphs() {
        for (String unsupported : List.of("हिन्दी", "中文", "Emoji 😀")) {
            assertThatThrownBy(() -> renderer.render(model(unsupported, null, List.of(new Topic(1L, "Topic")))))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(exception.getReason()).contains("cannot render metadata character U+");
                    });
        }
    }

    private static DownloadBookModel model(String title, Author author, List<Topic> topics) {
        return new DownloadBookModel(new Book(1L, title), author, topics);
    }

    private static String pageText(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document).replaceAll("\\s+", " ").strip();
    }

    private static void assertTextInsidePageMargins(PDDocument document) throws Exception {
        PDFTextStripper boundsChecker = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws IOException {
                for (TextPosition position : positions) {
                    assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(52f);
                    assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(544f);
                    assertThat(position.getYDirAdj()).isBetween(52f, 790f);
                }
                super.writeString(text, positions);
            }
        };
        boundsChecker.getText(document);
    }

    private static String uncheckedPageText(PDDocument document, int page) {
        try {
            return pageText(document, page);
        } catch (Exception exception) {
            throw new AssertionError("Cannot read rendered page " + page, exception);
        }
    }
}
