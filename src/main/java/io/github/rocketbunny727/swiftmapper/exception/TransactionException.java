package io.github.rocketbunny727.swiftmapper.exception;

public class TransactionException extends SwiftORMException {
    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}