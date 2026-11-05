package com.vendoryx.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory sliding-window rate limiter (per key, e.g. client IP).
 */
public final class IpRateLimiter {

    private final int maxRequests;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public IpRateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    /** @return true if request is allowed; false if limit exceeded */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            key = "unknown";
        }
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            if (q.size() >= maxRequests) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private void prune(Deque<Long> q, long now) {
        long cutoff = now - windowMs;
        Iterator<Long> it = q.iterator();
        while (it.hasNext()) {
            if (it.next() < cutoff) {
                it.remove();
            } else {
                break;
            }
        }
    }
}
