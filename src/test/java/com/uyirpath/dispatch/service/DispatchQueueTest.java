package com.uyirpath.dispatch.service;

import com.uyirpath.dispatch.model.Emergency;
import com.uyirpath.dispatch.model.EmergencyStatus;
import com.uyirpath.dispatch.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DispatchQueueTest {

    private static Emergency emergency(String id, int seq, Severity severity) {
        Emergency e = new Emergency();
        e.setId(id);
        e.setSeq(seq);
        e.setSeverity(severity);
        e.setStatus(EmergencyStatus.QUEUED);
        return e;
    }

    @Test
    @DisplayName("Priority queue orders CRITICAL before SERIOUS before MINOR")
    void ordersBySeverity() {
        DispatchQueue queue = new DispatchQueue();
        queue.add(emergency("EMG-MINOR", 1, Severity.MINOR));
        queue.add(emergency("EMG-SERIOUS", 2, Severity.SERIOUS));
        queue.add(emergency("EMG-CRITICAL", 3, Severity.CRITICAL));

        List<String> order = queue.snapshot().stream().map(Emergency::getId).toList();

        assertEquals(List.of("EMG-CRITICAL", "EMG-SERIOUS", "EMG-MINOR"), order);
        assertEquals("EMG-CRITICAL", queue.peek().getId());
    }

    @Test
    @DisplayName("Equal severity is served FIFO by database sequence number")
    void preservesFifoWithinSeverity() {
        DispatchQueue queue = new DispatchQueue();
        queue.add(emergency("EMG-C2", 9, Severity.CRITICAL));
        queue.add(emergency("EMG-C1", 4, Severity.CRITICAL));
        queue.add(emergency("EMG-C3", 12, Severity.CRITICAL));

        assertEquals("EMG-C1", queue.poll().getId());
        assertEquals("EMG-C2", queue.poll().getId());
        assertEquals("EMG-C3", queue.poll().getId());
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Adding the same emergency twice keeps a single queue entry")
    void deduplicatesById() {
        DispatchQueue queue = new DispatchQueue();
        Emergency first = emergency("EMG-DUP", 1, Severity.SERIOUS);
        Emergency second = emergency("EMG-DUP", 1, Severity.SERIOUS);
        second.setStatus(EmergencyStatus.QUEUED);

        queue.add(first);
        queue.add(second);

        assertEquals(1, queue.size());
        assertTrue(queue.contains("EMG-DUP"));
        queue.remove("EMG-DUP");
        assertFalse(queue.contains("EMG-DUP"));
        assertEquals(0, queue.size());
    }
}
