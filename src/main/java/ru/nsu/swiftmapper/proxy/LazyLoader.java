package ru.nsu.swiftmapper.proxy;

public interface LazyLoader {
    void load();
    boolean isLoaded();
}
