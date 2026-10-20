package sn.groupia.media.infrastructure.storage;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import sn.groupia.media.domain.model.MediaRendition;
import sn.groupia.media.domain.service.*;

public final class FilesystemMediaStorage implements MediaObjectStorage {
    private final Path root;

    public FilesystemMediaStorage(String directory) {
        try {
            root =
                    Files.createDirectories(Path.of(directory).toAbsolutePath().normalize())
                            .toRealPath();
        } catch (IOException ex) {
            throw new MediaStorageException();
        }
    }

    private Path resolve(String key) {
        Path result = root.resolve(MediaKeys.require(key)).normalize();
        if (!result.startsWith(root)) throw new MediaStorageException();
        for (Path path = result; path != null && !path.equals(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new MediaStorageException();
        return result;
    }

    @Override
    public void put(MediaRendition rendition, byte[] bytes) {
        Path temporary = null;
        try {
            Path target = resolve(rendition.key());
            Files.createDirectories(target.getParent());
            resolve(rendition.key());
            if (Files.exists(target)) {
                if (!head(rendition.key()).orElseThrow().sha256().equals(rendition.sha256()))
                    throw new MediaStorageException();
                return;
            }
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target);
            }
        } catch (IOException ex) {
            throw new MediaStorageException();
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    /* never distributed */
                }
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException ex) {
            throw new MediaStorageException();
        }
    }

    @Override
    public Optional<StoredObject> head(String key) {
        Path path = resolve(key);
        if (!Files.exists(path)) return Optional.empty();
        byte[] bytes = read(key);
        return Optional.of(new StoredObject(bytes.length, sha(bytes)));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new MediaStorageException();
        }
    }

    @Override
    public String resolveUrl(String key) {
        String[] parts = MediaKeys.require(key).split("/");
        return "/api/public/media/" + parts[4] + "/" + parts[6].split("\\.")[0];
    }

    public static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException();
        }
    }
}
