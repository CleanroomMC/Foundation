package top.outlands.foundation.boot;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A small, thread safe set of bare string prefixes
 */
public final class PrefixMatcher {
    
    private static final Comparator<String> SHORTEST_FIRST = Comparator.comparingInt(String::length);
    private static final String[] EMPTY = new String[0];
    private final Object writeLock = new Object();
    /** Immutable snapshot; replaced as a whole on write. */
    private volatile String[] prefixes = EMPTY;

    /**
     * @param prefix the prefix to store, must not be {@code null}
     * @return whether the prefix was added, {@code false} if it was already present
     */
    public boolean add(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        synchronized (writeLock) {
            String[] current = prefixes;
            for (String existing : current) {
                if (existing.equals(prefix)) {
                    return false;
                }
            }
            String[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = prefix;
            Arrays.sort(next, SHORTEST_FIRST);
            prefixes = next;
            return true;
        }
    }

    /**
     * Removes the prefix, so it stops matching and is no longer listed by {@link #prefixes()}.
     *
     * @param prefix the prefix to remove, must not be {@code null}
     * @return whether the prefix was present and has been removed
     */
    public boolean remove(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        synchronized (writeLock) {
            String[] current = prefixes;
            int index = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i].equals(prefix)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }
            String[] next = new String[current.length - 1];
            System.arraycopy(current, 0, next, 0, index);
            System.arraycopy(current, index + 1, next, index, current.length - index - 1);
            prefixes = next;
            return true;
        }
    }

    /**
     * @param name the name to look up
     * @return whether one of the stored prefixes is a prefix of {@code name}
     */
    public boolean matches(String name) {
        String[] snapshot = prefixes;
        for (int i = 0; i < snapshot.length; i++) {
            if (name.startsWith(snapshot[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return number of stored prefixes
     */
    public int size() {
        return prefixes.length;
    }

    /**
     * @return every stored prefix, shortest first
     */
    public List<String> prefixes() {
        return Collections.unmodifiableList(Arrays.asList(prefixes));
    }
}
