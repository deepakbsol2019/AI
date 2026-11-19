package com.exception.handle.repair.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.lang.model.SourceVersion;

import com.exception.handle.ai.model.RepairData.ApplyReceipt;
import com.exception.handle.ai.model.RepairData.ContextFile;
import com.exception.handle.ai.model.RepairData.SourceSnapshot;

/** Local-development source access. Does not call AI, compile, or restart. */
public final class SourceFileAccess {
    public static final int MAX_SOURCE_BYTES = 65_536;
    public static final int MAX_CONTEXT_FILE_BYTES = 32_768;
    public static final int MAX_CONTEXT_BYTES = 65_536;
    public static final int MAX_CONTEXT_FILES = 3;

    private static final int MAX_METADATA_BYTES = 16_384;
    private static final String HELPER_PACKAGE =
            SourceFileAccess.class.getPackageName();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path root;
    private final Path backupRoot;
    private final Path testRoot;
    private final List<String> allowedPackages;

    public SourceFileAccess(Path sourceRoot, Path backups,
                            List<String> allowedPackages, Path testRoot)
            throws IOException {
        this.root = Objects.requireNonNull(sourceRoot, "sourceRoot").toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("Source root is not a directory");
        }
        if (allowedPackages == null || allowedPackages.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one allowed application package is required");
        }
        if (allowedPackages.stream().anyMatch(p -> p == null || p.isBlank())) {
            throw new IllegalArgumentException(
                    "Allowed packages cannot contain null or blank entries");
        }
        this.allowedPackages = allowedPackages.stream()
                .map(String::trim).distinct().toList();
        if (this.allowedPackages.stream().anyMatch(p -> !SourceVersion.isName(p))) {
            throw new IllegalArgumentException(
                    "Use package names without wildcards");
        }

        Path requested = Objects.requireNonNull(backups, "backups")
                .toAbsolutePath().normalize();
        if (requested.startsWith(root)) {
            throw new IOException("Keep backups outside the source root");
        }
        // Check the existing ancestor before creating missing directories.
        Path ancestor = requested;
        while (ancestor != null
                && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("No existing ancestor for backup directory");
        }
        Path resolvedRequest = ancestor.toRealPath()
                .resolve(ancestor.relativize(requested)).normalize();
        if (resolvedRequest.startsWith(root)) {
            throw new IOException("Backup location resolves inside the source root");
        }
        Files.createDirectories(requested);
        this.backupRoot = requested.toRealPath();
        if (backupRoot.startsWith(root) || !Files.isDirectory(backupRoot)) {
            throw new IOException("Invalid backup directory");
        }

        Path availableTests = null;
        if (testRoot != null) {
            try {
                Path resolved = testRoot.toRealPath();
                if (Files.isDirectory(resolved)) {
                    availableTests = resolved;
                }
            } catch (IOException ignored) {
                // Test context is optional; a missing directory is allowed.
            }
        }
        this.testRoot = availableTests;
    }

    /** Returns null when no supported application frame can be resolved. */
    public SourceSnapshot read(Throwable exception) throws IOException {
    	System.out.println("Exception ::"+exception);
        Set<String> inspected = new HashSet<>();
        System.out.println("causeChain(exception) ::"+causeChain(exception));
        for (Throwable cause : causeChain(exception)) {
        	System.out.println("cause ::"+cause);
            for (StackTraceElement frame : cause.getStackTrace()) {
            	System.out.println("frame ::"+frame);
                String className = eligibleClass(frame);
                System.out.println("class name :: "+className);
                if (className == null || !inspected.add(className)) {
                    continue;
                }
                Path file;
                try {
                    file = resolveSource(className);
                } catch (NoSuchFileException | AccessDeniedException unavailable) {
                    continue;
                }
                System.out.println("Path ::"+file);
                if (file == null) {
                    continue;
                }
                String source = readLimited(file, MAX_SOURCE_BYTES);
                System.out.println("source ::"+source);
                return new SourceSnapshot(file, className, frame.getMethodName(),
                        frame.getLineNumber(), source);
            }
        }
        return null;
    }

    /** Optional bounded context; files are read, never compiled or executed. */
    public List<ContextFile> context(Throwable exception, SourceSnapshot main) {
        Objects.requireNonNull(main, "main");
        ContextCollector context = new ContextCollector(main.file());
        String stem = main.className().replace('.', '/');
        if (testRoot != null) {
            context.add(testRoot, stem + "Test.java", "test");
            context.add(testRoot, stem + "Tests.java", "test");
        }
        for (Throwable cause : causeChain(exception)) {
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (context.files.size() == MAX_CONTEXT_FILES) {
                    return List.copyOf(context.files);
                }
                String className = eligibleClass(frame);
                if (className != null && !className.equals(main.className())) {
                    context.add(root, className.replace('.', '/') + ".java",
                            "stack-frame");
                }
            }
        }
        return List.copyOf(context.files);
    }

    /** A receipt means replacement succeeded, even if history indexing failed. */
    public synchronized ApplyReceipt apply(SourceSnapshot before, String corrected)
            throws IOException {
        Objects.requireNonNull(before, "before");
        String relative = relativeFor(before.className());
        JavaSourceChecks.validate(before, corrected);
        Path file = assertUnchanged(before);
        Path directory = historyDirectory(relative, true);
        Instant createdAt = Instant.now();
        String simpleName = before.className()
                .substring(before.className().lastIndexOf('.') + 1);
        String stem = simpleName + "-" + FILE_TIME.format(createdAt)
                + "-" + UUID.randomUUID();
        Path backup = directory.resolve(stem + ".java.bak");
        Path record = directory.resolve(stem + ".properties");
        String beforeHash = sha256(before.source());
        String afterHash = sha256(corrected);

        // No REPLACE_EXISTING: preserve every original backup.
        Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        if (!beforeHash.equals(sha256(readLimited(backup, MAX_SOURCE_BYTES)))) {
            throw new IOException("Source changed while its backup was being copied");
        }
        Properties history = new Properties();
        history.setProperty("schema", "1");
        history.setProperty("source", relative);
        history.setProperty("backup", backup.getFileName().toString());
        history.setProperty("beforeSha256", beforeHash);
        history.setProperty("afterSha256", afterHash);
        history.setProperty("createdAt", createdAt.toString());
        history.setProperty("state", "PREPARED");
        writeProperties(record, history);

        Path temp = Files.createTempFile(file.getParent(), ".ai-repair-", ".tmp");
        try {
            Files.writeString(temp, corrected, StandardCharsets.UTF_8);
            assertUnchanged(before);
            // No non-atomic fallback. Compilation/restart are separate steps.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try {
                history.setProperty("state", "APPLIED");
                history.setProperty("appliedAt", Instant.now().toString());
                writeProperties(record, history);
                Properties latest = new Properties();
                latest.setProperty("schema", "1");
                latest.setProperty("source", relative);
                latest.setProperty("record", record.getFileName().toString());
                writeProperties(directory.resolve("latest.properties"), latest);
                return new ApplyReceipt(backup, record, true, null);
            } catch (IOException | RuntimeException metadataFailure) {
                return new ApplyReceipt(backup, record, false,
                        "Source replaced, but history/latest indexing failed: "
                                + metadataFailure.getMessage()
                                + ". Keep the returned backup and history paths "
                                + "for review.");
            }
        } finally {
            deleteTempQuietly(temp);
        }
    }

    /** Returns the exact indexed record; does not sort random UUID filenames. */
    public synchronized Properties latestRecord(String relativeJavaPath)
            throws IOException {
        String relative = checkedRelative(relativeJavaPath);
        Path directory = historyDirectory(relative, false);
        if (directory == null || !Files.exists(directory.resolve("latest.properties"),
                LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Properties latest = loadProperties(checkedChild(directory, "latest.properties"));
        if (!"1".equals(latest.getProperty("schema"))
                || !relative.equals(latest.getProperty("source"))) {
            throw new IOException("Latest pointer does not match the requested source");
        }
        String recordName = latest.getProperty("record");
        if (recordName == null || !recordName.endsWith(".properties")
                || recordName.equals("latest.properties")) {
            throw new IOException("Invalid history record reference");
        }
        Path record = checkedChild(directory, recordName);
        Properties history = loadProperties(record);
        if (!"1".equals(history.getProperty("schema"))
                || !"APPLIED".equals(history.getProperty("state"))
                || !relative.equals(history.getProperty("source"))) {
            throw new IOException(
                    "Latest history record is not an APPLIED entry for this source");
        }
        for (String key : List.of("beforeSha256", "afterSha256")) {
            if (!history.getProperty(key, "").matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid history hash: " + key);
            }
        }
        String backupName = history.getProperty("backup");
        if (backupName == null || !backupName.endsWith(".java.bak")) {
            throw new IOException("Invalid backup reference");
        }
        Path backup = checkedChild(directory, backupName);
        history.setProperty("manifestPath", record.toString());
        history.setProperty("backupPath", backup.toString());
        return history;
    }

    /** Explicit restore; creates its own backup, history record and latest entry. */
    public synchronized ApplyReceipt restoreLatest(String relativeJavaPath)
            throws IOException {
        String relative = checkedRelative(relativeJavaPath);
        Properties history = latestRecord(relative);
        if (history == null) {
            throw new IOException("No applied backup is indexed for this source");
        }
        String className = relative.substring(0, relative.length() - 5)
                .replace('/', '.');
        Path file = resolveSource(className);
        if (file == null) {
            throw new IOException("Source file is missing or is not a regular file");
        }
        String current = readLimited(file, MAX_SOURCE_BYTES);
        if (!sha256(current).equals(history.getProperty("afterSha256"))) {
            throw new IOException(
                    "Current source differs from the indexed apply; refusing "
                            + "to overwrite later edits");
        }
        String previous = readLimited(Path.of(history.getProperty("backupPath")),
                MAX_SOURCE_BYTES);
        if (!sha256(previous).equals(history.getProperty("beforeSha256"))) {
            throw new IOException("Backup content does not match its recorded SHA-256");
        }
        return apply(new SourceSnapshot(file, className, "<manual-restore>",
                -1, current), previous);
    }

    private Path assertUnchanged(SourceSnapshot before) throws IOException {
        Path expected = resolveSource(before.className());
        Path actual = before.file().toRealPath();
        if (expected == null || !actual.startsWith(root) || !actual.equals(expected)
                || !actual.equals(before.file())) {
            throw new IOException("Target path changed or is not allowed");
        }
        if (!readLimited(actual, MAX_SOURCE_BYTES).equals(before.source())) {
            throw new IOException("Source changed after it was read");
        }
        return actual;
    }

    private Path resolveSource(String className) throws IOException {
        Path requested = root.resolve(relativeFor(className));
        Path actual = requested.toRealPath();
        if (!actual.startsWith(root)) {
            throw new IOException("Source path escapes the allowed root");
        }
        return Files.isRegularFile(actual) ? actual : null;
    }

    private String eligibleClass(StackTraceElement frame) {
        String binaryName = frame.getClassName();
        String filename = frame.getFileName();
        int dot = binaryName.lastIndexOf('.');
        if (dot < 1 || filename == null || !filename.endsWith(".java")) {
            return null;
        }
        // Filename handles both Outer$Inner and a legal top-level Dollar$Name.
        String name = binaryName.substring(0, dot + 1)
                + filename.substring(0, filename.length() - 5);
        if (!allowedClass(name)) {
            return null;
        }
        return binaryName.equals(name) || binaryName.startsWith(name + "$")
                ? name : null;
    }

    private boolean allowedClass(String name) {
        if (name == null || !SourceVersion.isName(name)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 1) {
            return false;
        }
        String pkg = name.substring(0, dot);
        if (pkg.equals(HELPER_PACKAGE) || pkg.startsWith(HELPER_PACKAGE + ".")) {
            return false;
        }
        return allowedPackages.stream()
                .anyMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."));
    }

    private String relativeFor(String className) throws IOException {
        if (!allowedClass(className)) {
            throw new IOException("Class is outside the application allowlist");
        }
        return className.replace('.', '/') + ".java";
    }

    private String checkedRelative(String input) throws IOException {
        if (input == null || input.isBlank()) {
            throw new IOException("A relative Java source path is required");
        }
        String relative = input.replace('\\', '/');
        Path path;
        try {
            path = Path.of(relative);
        } catch (InvalidPathException invalid) {
            throw new IOException("Invalid relative source path", invalid);
        }
        if (path.isAbsolute() || !relative.endsWith(".java")
                || !path.normalize().toString().replace('\\', '/').equals(relative)) {
            throw new IOException("Use a normalized Java path relative to the source root");
        }
        String className = relative.substring(0, relative.length() - 5)
                .replace('/', '.');
        if (!relativeFor(className).equals(relative)) {
            throw new IOException("Invalid relative source path");
        }
        return relative;
    }

    private Path historyDirectory(String relative, boolean create) throws IOException {
        Path directory = backupRoot;
        // Resolve each component; reject locations outside the backup root.
        for (Path component : Path.of(relative + ".history")) {
            Path next = directory.resolve(component);
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    return null;
                }
                try {
                    Files.createDirectory(next);
                } catch (FileAlreadyExistsException raced) {
                    // Resolve and verify the existing entry below.
                }
            }
            directory = next.toRealPath();
            if (!directory.startsWith(backupRoot) || !Files.isDirectory(directory)) {
                throw new IOException(
                        "History directory escapes the backup root or is not a directory");
            }
        }
        return directory;
    }

    private Path checkedChild(Path directory, String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            throw new IOException("Invalid history filename");
        }
        Path file = directory.resolve(name).toRealPath();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) {
            throw new IOException("History file is outside its target directory");
        }
        return file;
    }

    private static List<Throwable> causeChain(Throwable exception) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = exception; t != null && seen.add(t); t = t.getCause()) {
            chain.add(t);
        }
        Collections.reverse(chain);
        return chain;
    }

    private static String readLimited(Path file, int maximum) throws IOException {
        if (Files.size(file) > maximum) {
            throw new IOException("File exceeds " + maximum + " byte limit");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(maximum + 1);
        }
        if (bytes.length > maximum) {
            throw new IOException("File exceeds " + maximum + " byte limit");
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Required SHA-256 implementation is unavailable",
                    impossible);
        }
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new StringReader(readLimited(file, MAX_METADATA_BYTES))) {
            properties.load(reader);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Malformed history properties: " + file, invalid);
        }
        return properties;
    }

    private static void writeProperties(Path file, Properties properties)
            throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".history-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, "Repair source history");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteTempQuietly(temp);
        }
    }

    private static void deleteTempQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException | RuntimeException ignored) {
            // Cleanup must not turn a successful replacement into a failure result.
        }
    }

    private final class ContextCollector {
        private final List<ContextFile> files = new ArrayList<>();
        private final Set<Path> seen = new HashSet<>();
        private int bytes;

        private ContextCollector(Path main) {
            seen.add(main);
        }

        private void add(Path contextRoot, String relative, String role) {
            if (files.size() >= MAX_CONTEXT_FILES) {
                return;
            }
            try {
                Path file = contextRoot.resolve(relative).normalize().toRealPath();
                if (!file.startsWith(contextRoot) || !Files.isRegularFile(file)
                        || !seen.add(file)) {
                    return;
                }
                String content = readLimited(file, MAX_CONTEXT_FILE_BYTES);
                int count = content.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + count > MAX_CONTEXT_BYTES) {
                    return;
                }
                files.add(new ContextFile(contextRoot.relativize(file).toString()
                        .replace('\\', '/'), role, content));
                bytes += count;
            } catch (IOException | RuntimeException unavailable) {
                // Optional context may be absent, unreadable or too large.
            }
        }
    }
}
