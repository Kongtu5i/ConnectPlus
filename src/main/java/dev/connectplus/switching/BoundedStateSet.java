package dev.connectplus.switching;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.ToLongFunction;

/** Event-loop confined set with an atomic cumulative entry/estimated-byte budget. */
final class BoundedStateSet<T> extends AbstractSet<T> {
    private final Set<T> values = new LinkedHashSet<>();
    private final int maxEntries;
    private final long maxBytes;
    private final ToLongFunction<T> weight;
    private long bytes;
    BoundedStateSet(int maxEntries, long maxBytes, ToLongFunction<T> weight) {
        this.maxEntries = maxEntries; this.maxBytes = maxBytes; this.weight = weight;
    }
    @Override public boolean add(T value) { return addAll(java.util.List.of(value)); }
    @Override public boolean addAll(Collection<? extends T> incoming) {
        Set<T> additions = new HashSet<>();
        long extra = 0;
        for (T value : incoming) {
            if (!values.contains(value) && additions.add(value)) {
                extra += weight.applyAsLong(value);
                if (additions.size() > maxEntries - values.size() || extra > maxBytes - bytes) throw new StateLimitException();
            }
        }
        for (T value : incoming) values.add(value); // Preserve wire insertion order.
        bytes += extra;
        return !additions.isEmpty();
    }
    @Override public boolean remove(Object value) {
        if (!values.remove(value)) return false;
        @SuppressWarnings("unchecked") T item = (T)value;
        bytes -= weight.applyAsLong(item); return true;
    }
    @Override public void clear() { values.clear(); bytes = 0; }
    @Override public int size() { return values.size(); }
    @Override public boolean contains(Object value) { return values.contains(value); }
    @Override public Iterator<T> iterator() {
        Iterator<T> delegate = values.iterator();
        return new Iterator<>() {
            T current;
            public boolean hasNext() { return delegate.hasNext(); }
            public T next() { return current = delegate.next(); }
            public void remove() { delegate.remove(); bytes -= weight.applyAsLong(current); }
        };
    }
}
