package com.rocketbunny.swiftmapper.proxy;

import com.rocketbunny.swiftmapper.exception.LazyLoadingException;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class LazyList<E> implements List<E> {
    private volatile List<E> delegate;
    private final Runnable loader;
    private volatile boolean loaded = false;
    private final Object initializationLock = new Object();

    public LazyList(Runnable loader) {
        this.loader = Objects.requireNonNull(loader, "Loader cannot be null");
        this.delegate = new CopyOnWriteArrayList<>();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }

        synchronized (initializationLock) {
            if (loaded) {
                return;
            }

            List<E> loadedData = new ArrayList<>();
            try {
                loader.run();
            } catch (Exception e) {
                throw new LazyLoadingException("Failed to load lazy list data", e);
            }

            loaded = true;
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    void internalSetDelegate(List<E> delegate) {
        this.delegate = delegate != null ? new CopyOnWriteArrayList<>(delegate) : new CopyOnWriteArrayList<>();
    }

    @Override
    public int size() {
        ensureLoaded();
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        ensureLoaded();
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        ensureLoaded();
        return delegate.contains(o);
    }

    @Override
    public Iterator<E> iterator() {
        ensureLoaded();
        return delegate.iterator();
    }

    @Override
    public Object[] toArray() {
        ensureLoaded();
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        ensureLoaded();
        return delegate.toArray(a);
    }

    @Override
    public boolean add(E e) {
        if (!loaded) {
            ensureLoaded();
        }
        return delegate.add(e);
    }

    @Override
    public boolean remove(Object o) {
        ensureLoaded();
        return delegate.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        ensureLoaded();
        return delegate.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (!loaded) {
            ensureLoaded();
        }
        return delegate.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        if (!loaded) {
            ensureLoaded();
        }
        return delegate.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        ensureLoaded();
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        ensureLoaded();
        return delegate.retainAll(c);
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        ensureLoaded();
        delegate.replaceAll(operator);
    }

    @Override
    public void sort(Comparator<? super E> c) {
        ensureLoaded();
        List<E> sorted = new ArrayList<>(delegate);
        sorted.sort(c);
        delegate = new CopyOnWriteArrayList<>(sorted);
    }

    @Override
    public void clear() {
        if (loaded) {
            delegate.clear();
        } else {
            loaded = true;
            delegate = new CopyOnWriteArrayList<>();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        ensureLoaded();
        return delegate.equals(o);
    }

    @Override
    public int hashCode() {
        ensureLoaded();
        return delegate.hashCode();
    }

    @Override
    public E get(int index) {
        ensureLoaded();
        return delegate.get(index);
    }

    @Override
    public E set(int index, E element) {
        ensureLoaded();
        return delegate.set(index, element);
    }

    @Override
    public void add(int index, E element) {
        if (!loaded) {
            ensureLoaded();
        }
        delegate.add(index, element);
    }

    @Override
    public E remove(int index) {
        ensureLoaded();
        return delegate.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        ensureLoaded();
        return delegate.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        ensureLoaded();
        return delegate.lastIndexOf(o);
    }

    @Override
    public ListIterator<E> listIterator() {
        ensureLoaded();
        return delegate.listIterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        ensureLoaded();
        return delegate.listIterator(index);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        ensureLoaded();
        return delegate.subList(fromIndex, toIndex);
    }

    @Override
    public Spliterator<E> spliterator() {
        ensureLoaded();
        return delegate.spliterator();
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        ensureLoaded();
        return delegate.removeIf(filter);
    }

    @Override
    public Stream<E> stream() {
        ensureLoaded();
        return delegate.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        ensureLoaded();
        return delegate.parallelStream();
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        ensureLoaded();
        delegate.forEach(action);
    }

    public void setDelegate(List<E> delegate) {
        synchronized (initializationLock) {
            this.delegate = delegate != null ? new CopyOnWriteArrayList<>(delegate) : new CopyOnWriteArrayList<>();
            this.loaded = true;
        }
    }
}