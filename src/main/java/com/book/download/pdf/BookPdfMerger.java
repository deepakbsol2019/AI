package com.book.download.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Each invocation owns its documents. Source pages are cloned by PDFBox, never recreated as HTML. */
@Component
public class BookPdfMerger {
    public record SourcePdf(Path path, boolean allowEmpty) { }

    public byte[] merge(byte[] frontMatter, List<SourcePdf> sources) {
        PDFMergerUtility merger = new PDFMergerUtility();
        try (PDDocument result = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (frontMatter != null) {
                try (PDDocument front = Loader.loadPDF(frontMatter)) {
                    if (front.getNumberOfPages() == 0) {
                        throw failure("Generated cover and contents PDF is empty.", null);
                    }
                    merger.appendDocument(result, front);
                }
            }
            for (SourcePdf source : sources) {
                append(merger, result, source);
            }
            if (result.getNumberOfPages() == 0) {
                throw failure("The book has no PDF pages to download.", null);
            }
            result.save(output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw failure("Could not merge the book PDF.", exception);
        }
    }

    private void append(PDFMergerUtility merger, PDDocument result, SourcePdf source) {
        // File-backed loading avoids another in-memory copy of every uploaded PDF.
        try (PDDocument document = Loader.loadPDF(source.path().toFile())) {
            if (document.getNumberOfPages() == 0 && !source.allowEmpty()) {
                throw failure("Stored topic PDF '" + source.path().getFileName() + "' has no pages.", null);
            }
            merger.appendDocument(result, document);
        }
        catch (IOException exception) {
            throw failure("Could not read or merge stored topic PDF '" + source.path().getFileName() + "'.", exception);
        }
    }

    private static ResponseStatusException failure(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
