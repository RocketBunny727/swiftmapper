package com.rocketbunny.swiftmapper.exception;

public class LazyLoadingException extends RuntimeException {
    public LazyLoadingException(String message) {
        super(message);
    }

    public LazyLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
