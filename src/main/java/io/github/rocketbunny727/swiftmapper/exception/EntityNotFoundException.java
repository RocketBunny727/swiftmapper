package io.github.rocketbunny727.swiftmapper.exception;

public class EntityNotFoundException extends SwiftORMException {
    public EntityNotFoundException(String message) {
        super(message, null);
    }
}
