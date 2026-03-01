package com.rocketbunny.swiftmapper.exception;

public class ProxyCreationException extends RuntimeException {
    public ProxyCreationException(String message) {
        super(message);
    }

    public ProxyCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
