package com.yuelengm.nhkantan.market.order;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Writer-only replay guard. Persist high-water marks even after bounded response entries have been evicted. */
public final class RequestJournal<T> {

    private final Map<UUID, Long> highest = new HashMap<>();
    private final LinkedHashMap<String, T> replies = new LinkedHashMap<>();
    private final int capacity;

    public RequestJournal(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Response cache capacity must be positive");
        this.capacity = capacity;
    }

    public T execute(UUID owner, long sequence, Supplier<T> action) {
        if (owner == null || sequence < 1) throw new IllegalArgumentException("Invalid request identity");
        String key = owner + ":" + sequence;
        T prior = replies.get(key);
        if (prior != null) return prior;
        if (sequence <= highest.getOrDefault(owner, 0L)) {
            throw new IllegalArgumentException("Request already processed; refresh the account snapshot");
        }
        // Even rejected/throwing requests consume their ID; retries must not apply partially executed work again.
        highest.put(owner, sequence);
        T result = action.get();
        if (result == null) throw new IllegalStateException("Command returned no result");
        replies.put(key, result);
        while (replies.size() > capacity) replies.remove(
            replies.keySet()
                .iterator()
                .next());
        return result;
    }

    public Map<UUID, Long> checkpoint() {
        return new HashMap<>(highest);
    }

    public long nextSequence(UUID owner) {
        return Math.addExact(highest.getOrDefault(owner, 0L), 1);
    }

    public void restore(Map<UUID, Long> marks) {
        if (!highest.isEmpty() || !replies.isEmpty() || marks == null || marks.size() > 100000) {
            throw new IllegalArgumentException("Invalid replay checkpoint");
        }
        for (Map.Entry<UUID, Long> mark : marks.entrySet()) {
            if (mark.getKey() == null || mark.getValue() == null || mark.getValue() < 1) {
                throw new IllegalArgumentException("Invalid request high-water mark");
            }
        }
        highest.putAll(marks);
    }
}
