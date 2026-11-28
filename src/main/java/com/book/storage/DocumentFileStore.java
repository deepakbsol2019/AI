package com.book.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Uses the existing tenant filesystem boundary; callers persist the returned locator verbatim. */
@Component
public class DocumentFileStore {
    private final TenantStoragePaths paths;
    public DocumentFileStore(TenantStoragePaths paths){this.paths=paths;}

    public Path write(long tenant,long user,String validatedType,String bookName,String leaf,Path source) throws IOException {
        paths.requireWritable(tenant);
        Path folder=paths.documentDirectory(tenant,user,validatedType,bookName);
        String safe=safeFilename(leaf);
        Path output=folder.resolve(safe);
        for(int attempt=0;attempt<5;attempt++) {
            if(attempt>0)output=folder.resolve(suffix(safe));
            boolean owned=false;
            try(var input=Files.newInputStream(source)) {
                // Exclusive creation is intentional: neither sanitized collisions nor retries overwrite data.
                var stream=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                owned=true;
                try(stream){input.transferTo(stream);}
                paths.requireWritable(tenant);
                return paths.requirePath(tenant,output);
            } catch(FileAlreadyExistsException collision) {
                if(owned)Files.deleteIfExists(output);
            } catch(IOException|RuntimeException failure) {
                if(owned)try{Files.deleteIfExists(output);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        throw new IOException("Could not allocate an exclusive document object.");
    }
    /**
     * Allocate an exclusive UUID object, durably register its locator, then copy upload bytes.
     * The callback must commit its manifest independently; it runs before opening the source.
     * A registration failure can leave at most an empty placeholder if cleanup is unavailable.
     */
    public Path writeTracked(long tenant,long user,String validatedType,String bookName,String leaf,Path source,
            Consumer<Path> register) throws IOException {
        java.util.Objects.requireNonNull(register,"A durable storage-locator callback is required.");
        paths.requireWritable(tenant);
        Path folder=paths.documentDirectory(tenant,user,validatedType,bookName);
        String safe=safeFilename(leaf);
        for(int attempt=0;attempt<5;attempt++) {
            Path output=folder.resolve(suffix(safe));boolean owned=false;
            try {
                var stream=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);owned=true;
                try(stream) {
                    Path locator=paths.requirePath(tenant,output);
                    register.accept(locator);
                    try(var input=Files.newInputStream(source)){input.transferTo(stream);}
                    paths.requireWritable(tenant);
                    return locator;
                }
            } catch(FileAlreadyExistsException collision) {
                if(owned)Files.deleteIfExists(output);
            } catch(IOException|RuntimeException failure) {
                if(owned)try{Files.deleteIfExists(output);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        throw new IOException("Could not allocate an exclusive tracked document object.");
    }
    public Path readable(long tenant,String locator) throws IOException {return paths.readableFile(tenant,locator);}
    public byte[] read(long tenant,String locator) throws IOException {
        Path file=readable(tenant,locator);
        if(Files.size(file)>100L*1024*1024)throw new IOException("The binary response exceeds the 100 MiB download limit.");
        return Files.readAllBytes(file);
    }
    public void delete(long tenant,String locator) throws IOException {
        if(locator==null||locator.isBlank())return;
        Path candidate=Path.of(locator).toAbsolutePath().normalize();
        if(!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))return;
        Files.delete(paths.readableFile(tenant,locator));
    }
    public static String safeFilename(String value) {
        if(value==null||value.isBlank())throw new IllegalArgumentException("A document filename is required.");
        String safe=value.strip().replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]","_").replaceFirst("[. ]+$","");
        if(safe.isBlank())safe="document";
        if(safe.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$"))safe="_"+safe;
        if(safe.length()>200){int dot=safe.lastIndexOf('.');String extension=dot>=0&&safe.length()-dot<12?safe.substring(dot):"";safe=safe.substring(0,180)+extension;}
        return TenantStoragePaths.filename(safe);
    }
    private static String suffix(String filename){int dot=filename.lastIndexOf('.');String id="_"+UUID.randomUUID();return dot>0?filename.substring(0,dot)+id+filename.substring(dot):filename+id;}
}
