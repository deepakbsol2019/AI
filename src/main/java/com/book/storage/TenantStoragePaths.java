package com.book.storage;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.security.TenantContext;

/** Single filesystem boundary. Tenant IDs are server-issued positive longs, never path fragments. */
@Component
public class TenantStoragePaths {
    private final Path base;
    public TenantStoragePaths(@Value("${app.topic-pdf.storage-root:C:/workspace/backend/Book_Search/upload-pdf}") String configured) {
        if (configured == null || configured.isBlank() || !Path.of(configured).isAbsolute())
            throw new IllegalArgumentException("Configure an absolute storage path for this deployment platform.");
        base=Path.of(configured).normalize();
    }
    public Path base() { return base; }
    public Path provision(long tenantId) {
        requireWritable(tenantId);
        Path root=tenantPath(tenantId);
        try {
            createSafeDirectories(root);
            if (!Files.isReadable(root) || !Files.isWritable(root)) throw new IOException("Tenant storage is not accessible.");
            Path probe=Files.createTempFile(root,".storage-probe-",".tmp");
            Files.delete(probe);
            return root;
        } catch(IOException | SecurityException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Tenant storage could not be provisioned. Contact the administrator.");
        }
    }
    public Path currentRoot() { return provision(TenantContext.requireTenantId()); }
    /** Common path for new writes; persisted legacy locators remain readable without reconstruction. */
    public Path documentDirectory(long tenantId,long userId,String validatedType,String bookName) throws IOException {
        if(userId<=0)throw new IllegalArgumentException("A positive trusted uploader ID is required.");
        String type=validatedType==null?"":validatedType.toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("PDF","CSV","XLSX","XLS").contains(type))throw new IllegalArgumentException("A validated supported document format is required.");
        Path folder=provision(tenantId).resolve(type).resolve(Long.toString(userId)).resolve(folderName(bookName));
        createSafeDirectories(folder);
        return requirePath(tenantId,folder);
    }
    public void requireWritable(long tenantId) {
        if(Files.exists(marker(tenantId),LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Tenant storage is locked for deletion cleanup.");
    }
    private Path marker(long tenantId) {tenantPath(tenantId);return base.resolve(".tenant-deletions").resolve(Long.toString(tenantId));}
    public void lockDeletion(long tenantId,String jobKey) throws IOException {
        Path marker=marker(tenantId);createSafeDirectories(marker.getParent());
        try {Files.writeString(marker,jobKey,StandardOpenOption.CREATE_NEW);}
        catch(FileAlreadyExistsException existing) {
            checkAncestors(marker);if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) || !Files.readString(marker).equals(jobKey)) throw new IOException("Storage belongs to another deletion job.");
        }
    }
    public void unlockDeletion(long tenantId,String jobKey) throws IOException {
        Path marker=marker(tenantId);
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) {checkAncestors(marker);if(!Files.readString(marker).equals(jobKey))throw new IOException("Deletion lock mismatch.");Files.delete(marker);}
    }
    public Path tenantPath(long tenantId) {
        if(tenantId<=0) throw new IllegalArgumentException("A positive tenant ID is required.");
        return base.resolve(Long.toString(tenantId));
    }
    public Path requirePath(long tenantId,Path path) throws IOException {
        Path root=tenantPath(tenantId), candidate=path.toAbsolutePath().normalize();
        if(!candidate.startsWith(root)) throw new IOException("File is outside tenant storage.");
        checkAncestors(candidate);
        if(!candidate.toRealPath().startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw new IOException("File resolves outside tenant storage.");
        return candidate;
    }
    public Path readableFile(long tenantId,String stored) throws IOException {
        if(stored==null || stored.isBlank()) throw new IOException("No stored file.");
        Path supplied=Path.of(stored);
        if (stored.contains("\\") && java.io.File.separatorChar!='\\') throw new IOException("Invalid path for this platform.");
        for(Path part:supplied) if(part.toString().equals("..")) throw new IOException("Traversal is forbidden.");
        Path file=requirePath(tenantId,supplied.isAbsolute()?supplied:tenantPath(tenantId).resolve(supplied));
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) throw new IOException("File is unavailable.");
        return file.toRealPath();
    }
    public static String filename(String value) {
        if(value==null || value.isBlank() || value.length()>255 || value.equals(".") || value.equals("..")
                || value.matches(".*[<>:\"/\\\\|?*\\x00-\\x1f\\x7f].*") || value.endsWith(".") || value.endsWith(" ")
                || value.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$"))
            throw new IllegalArgumentException("Unsafe storage filename.");
        return value;
    }
    /** Existing book-name convention: keep normal text, sanitize unsafe filesystem characters only. */
    public static String folderName(String value) {
        if(value==null || value.isBlank())throw new IllegalArgumentException("A nonempty file/book name is required.");
        String safe=value.strip().replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]","_").replaceFirst("[. ]+$","");
        if(safe.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$"))safe="_"+safe;
        return filename(safe);
    }
    private static void createSafeDirectories(Path path) throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            Path parent=path.getParent();if(parent!=null) createSafeDirectories(parent);
            try { Files.createDirectory(path); } catch(FileAlreadyExistsException concurrent) { /* validate below */ }
        }
        checkAncestors(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Storage path is not a directory.");
    }
    private static void checkAncestors(Path path) throws IOException {
        for(Path current=path;current!=null;current=current.getParent()) {
            // Windows expands ordinary 8.3 names (e.g. DEEPAK~1) in both forms.
            // Compare canonical forms so aliases work while actual link/junction redirects fail.
            if(Files.isSymbolicLink(current) || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Symbolic links and redirected storage paths are forbidden.");
        }
    }
}
