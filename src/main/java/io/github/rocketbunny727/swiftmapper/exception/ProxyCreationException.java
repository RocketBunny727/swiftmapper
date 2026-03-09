package io.github.rocketbunny727.swiftmapper.exception;

public class ProxyCreationException extends SwiftORMException {
    public ProxyCreationException(String message) {
        super(message);
    }

    public ProxyCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
