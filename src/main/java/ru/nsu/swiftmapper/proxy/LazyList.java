package ru.nsu.swiftmapper.proxy;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class LazyList<E> implements List<E> {
    private List<E> delegate;
    private final Runnable loader;
    private boolean loaded = false;

    public LazyList(Runnable loader) {
        this.loader = loader;
        this.delegate = new ArrayList<>();
    }

    private void ensureLoaded() {
        if (!loaded) {
            loader.run();
            loaded = true;
        }
    }

    public boolean isLoaded() {
        return loaded;
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
        ensureLoaded();
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
        ensureLoaded();
        return delegate.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        ensureLoaded();
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
        delegate.sort(c);
    }

    @Override
    public void clear() {
        ensureLoaded();
        delegate.clear();
    }

    @Override
    public boolean equals(Object o) {
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
        ensureLoaded();
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
        this.delegate = delegate;
        this.loaded = true;
    }
}