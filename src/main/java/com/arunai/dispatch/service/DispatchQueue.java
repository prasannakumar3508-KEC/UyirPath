package com.arunai.dispatch.service;

import com.arunai.dispatch.model.Emergency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Priority queue for waiting emergencies.
 *
 * <p>CRITICAL &lt; SERIOUS &lt; MINOR, then FIFO using the database sequence
 * number for equal severity. This is the exact ordering tested by the unit
 * tests. The database remains the durable copy; this queue is rebuilt from
 * QUEUED rows at startup.</p>
 */
public class DispatchQueue {
    private static final Comparator<Emergency> ORDER =
            Comparator.comparingInt(Emergency::priority).thenComparingInt(Emergency::getSeq);

    private final PriorityQueue<Emergency> queue = new PriorityQueue<>(ORDER);

    public synchronized void add(Emergency emergency) {
        remove(emergency.getId());
        queue.add(emergency);
    }

    public synchronized Emergency peek() { return queue.peek(); }

    public synchronized Emergency poll() { return queue.poll(); }

    public synchronized void remove(String emergencyId) {
        queue.removeIf(e -> e.getId().equals(emergencyId));
    }

    public synchronized boolean contains(String emergencyId) {
        return queue.stream().anyMatch(e -> e.getId().equals(emergencyId));
    }

    public synchronized List<Emergency> snapshot() {
        List<Emergency> copy = new ArrayList<>(queue);
        copy.sort(ORDER);
        return copy;
    }

    public synchronized void clear() { queue.clear(); }

    public synchronized int size() { return queue.size(); }
}
