package com.book.user.service;

import com.book.storage.TenantStoragePaths;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Exact server-created manifest, never a client path or a recursive delete command. */
@Component
public class UserDeletionFiles {
    final TenantStoragePaths storage;
    public UserDeletionFiles(TenantStoragePaths storage){this.storage=storage;}
    public record Entry(String relative,boolean directory,long size,String sha256){}
    public record Manifest(String root,List<Entry> entries){}
    public Manifest snapshot(long tenant) throws IOException {
        Path root=storage.tenantPath(tenant);var entries=new ArrayList<Entry>();
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)){
            storage.requirePath(tenant,root);
            try(var walk=Files.walk(root)){for(Path path:walk.toList()){
                storage.requirePath(tenant,path);boolean directory=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
                if(!directory && !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsupported storage entry.");
                entries.add(new Entry(root.relativize(path).toString(),directory,directory?0:Files.size(path),directory?null:digest(path)));
            }}
        }
        return new Manifest(root.toString(),List.copyOf(entries));
    }
    public void delete(long tenant,Manifest manifest) throws IOException {
        Path root=storage.tenantPath(tenant);
        if(!root.toString().equals(manifest.root()))throw new IOException("Configured storage root differs from the durable manifest.");
        Set<Path> expected=new HashSet<>();
        for(Entry entry:manifest.entries()){
            Path path=resolve(root,entry);if(!expected.add(path))throw new IOException("Duplicate manifest path.");
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
                storage.requirePath(tenant,path);
                if(entry.directory()!=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Storage entry type changed.");
                if(!entry.directory() && (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=entry.size() || !digest(path).equals(entry.sha256())))throw new IOException("Storage content changed after confirmation.");
            }
        }
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)){
            try(var walk=Files.walk(root)){for(Path path:walk.toList()){storage.requirePath(tenant,path);if(!expected.contains(path))throw new IOException("Unexpected storage entries require review.");}}
        }
        var ordered=new ArrayList<>(manifest.entries());ordered.sort(Comparator.comparingInt((Entry e)->resolve(root,e).getNameCount()).reversed());
        for(Entry entry:ordered){Path path=resolve(root,entry);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){storage.requirePath(tenant,path);Files.delete(path);}}
    }
    private Path resolve(Path root,Entry entry) throws IOExceptionUnchecked {
        Path relative=Path.of(entry.relative());if(relative.isAbsolute())throw new IOExceptionUnchecked();
        for(Path part:relative)if(part.toString().equals(".."))throw new IOExceptionUnchecked();
        Path resolved=root.resolve(relative).normalize();if(!resolved.startsWith(root))throw new IOExceptionUnchecked();return resolved;
    }
    private static final class IOExceptionUnchecked extends IllegalArgumentException {IOExceptionUnchecked(){super("Unsafe deletion manifest.");}}
    private String digest(Path path) throws IOException {
        try {var hash=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] bytes=new byte[8192];for(int n;(n=input.read(bytes))!=-1;)hash.update(bytes,0,n);}return HexFormat.of().formatHex(hash.digest());}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
