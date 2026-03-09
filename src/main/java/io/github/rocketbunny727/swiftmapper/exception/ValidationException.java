package io.github.rocketbunny727.swiftmapper.exception;

public class ValidationException extends SwiftORMException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) { super(message, cause); }
}
