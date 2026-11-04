package org.jwcarman.noir.commit;

/** A file under a sealed directory cannot be canonicalized (symlink, non-UTF-8, unreadable). */
public class SealedFileException extends RuntimeException {
    public SealedFileException(String message) {
        super(message);
    }

    public SealedFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
