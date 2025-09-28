package com.wildex999.tickdynamic.listinject;

import java.util.*;

/**
 * Read-only tracking wrapper for a List that holds TileEntity instances.
 * It delegates all operations to the underlying list, but when elements are accessed
 * (get/iterator.next/listIterator.next), it records the element into CustomProfiler.LAST_TE_FETCHED
 * if the current world's profiler is in tile processing.
 *
 * This allows per-tile timing without swapping or reordering the tickableTileEntities list.
 */
public final class TrackingList<E> implements List<E>, RandomAccess {
    private final List<E> delegate;
    private final CustomProfiler profiler;

    public TrackingList(List<E> delegate, CustomProfiler profiler) {
        this.delegate = delegate;
        this.profiler = profiler;
    }

    @SuppressWarnings("unchecked")
    private void track(E e) {
        if (e == null) return;
        if (profiler == null) return;
        if (e instanceof net.minecraft.tileentity.TileEntity) {
            net.minecraft.tileentity.TileEntity te = (net.minecraft.tileentity.TileEntity) e;
            try {
                CustomProfiler.LAST_TE_FETCHED.set(te);
                profiler.manualSwitchTe(te);
            } catch(Throwable ignore) {}
        }
    }

    // Basic sized accessors
    @Override public int size() { return delegate.size(); }
    @Override public boolean isEmpty() { return delegate.isEmpty(); }
    @Override public boolean contains(Object o) { return delegate.contains(o); }

    @Override
    public Iterator<E> iterator() {
        final Iterator<E> it = delegate.iterator();
        return new Iterator<E>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public E next() { E e = it.next(); track(e); return e; }
            @Override public void remove() { it.remove(); }
        };
    }

    @Override public Object[] toArray() { return delegate.toArray(); }
    @Override public <T> T[] toArray(T[] a) { return delegate.toArray(a); }

    // Mutators delegate directly
    @Override public boolean add(E e) { return delegate.add(e); }
    @Override public boolean remove(Object o) { return delegate.remove(o); }
    @Override public boolean containsAll(Collection<?> c) { return delegate.containsAll(c); }
    @Override public boolean addAll(Collection<? extends E> c) { return delegate.addAll(c); }
    @Override public boolean addAll(int index, Collection<? extends E> c) { return delegate.addAll(index, c); }
    @Override public boolean removeAll(Collection<?> c) { return delegate.removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { return delegate.retainAll(c); }
    @Override public void clear() { delegate.clear(); }

    // Indexed operations (track on read)
    @Override public E get(int index) { E e = delegate.get(index); track(e); return e; }
    @Override public E set(int index, E element) { return delegate.set(index, element); }
    @Override public void add(int index, E element) { delegate.add(index, element); }
    @Override public E remove(int index) { return delegate.remove(index); }
    @Override public int indexOf(Object o) { return delegate.indexOf(o); }
    @Override public int lastIndexOf(Object o) { return delegate.lastIndexOf(o); }

    @Override
    public ListIterator<E> listIterator() {
        final ListIterator<E> it = delegate.listIterator();
        return new ListIterator<E>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public E next() { E e = it.next(); track(e); return e; }
            @Override public boolean hasPrevious() { return it.hasPrevious(); }
            @Override public E previous() { E e = it.previous(); track(e); return e; }
            @Override public int nextIndex() { return it.nextIndex(); }
            @Override public int previousIndex() { return it.previousIndex(); }
            @Override public void remove() { it.remove(); }
            @Override public void set(E e) { it.set(e); }
            @Override public void add(E e) { it.add(e); }
        };
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        final ListIterator<E> it = delegate.listIterator(index);
        return new ListIterator<E>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public E next() { E e = it.next(); track(e); return e; }
            @Override public boolean hasPrevious() { return it.hasPrevious(); }
            @Override public E previous() { E e = it.previous(); track(e); return e; }
            @Override public int nextIndex() { return it.nextIndex(); }
            @Override public int previousIndex() { return it.previousIndex(); }
            @Override public void remove() { it.remove(); }
            @Override public void set(E e) { it.set(e); }
            @Override public void add(E e) { it.add(e); }
        };
    }

    @Override public List<E> subList(int fromIndex, int toIndex) { return delegate.subList(fromIndex, toIndex); }

    // equals/hashCode/toString delegate
    @Override public boolean equals(Object o) { return delegate.equals(o); }
    @Override public int hashCode() { return delegate.hashCode(); }
    @Override public String toString() { return delegate.toString(); }
}
