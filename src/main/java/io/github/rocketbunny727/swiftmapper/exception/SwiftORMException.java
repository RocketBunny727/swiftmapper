package io.github.rocketbunny727.swiftmapper.exception;

public class SwiftORMException extends RuntimeException {
    public SwiftORMException(String message) {
        super(message);
    }

    public SwiftORMException(String message, Throwable cause) {
        super(message, cause);
    }
}
