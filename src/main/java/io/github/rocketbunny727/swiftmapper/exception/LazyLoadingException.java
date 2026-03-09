package io.github.rocketbunny727.swiftmapper.exception;

public class LazyLoadingException extends SwiftORMException {
    public LazyLoadingException(String message) {
        super(message);
    }

    public LazyLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
