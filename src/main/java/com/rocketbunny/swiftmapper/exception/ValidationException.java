package com.rocketbunny.swiftmapper.exception;

public class ValidationException extends SwiftORMException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) { super(message, cause); }
}
