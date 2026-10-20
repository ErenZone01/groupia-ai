package sn.groupia.media.domain.service;

/** Deliberately carries no provider response, URL, credentials or original exception. */
public final class MediaStorageException extends RuntimeException {
    public MediaStorageException() {
        super("MEDIA_STORAGE_UNAVAILABLE");
    }
}
