package com.uyirpath.dispatch.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyStatusTest {

    @Test
    @DisplayName("Emergency lifecycle accepts only the documented transitions")
    void validatesLifecycleTransitions() {
        assertTrue(EmergencyStatus.REPORTED.canTransitionTo(EmergencyStatus.QUEUED));
        assertTrue(EmergencyStatus.QUEUED.canTransitionTo(EmergencyStatus.ASSIGNED));
        assertTrue(EmergencyStatus.ASSIGNED.canTransitionTo(EmergencyStatus.AMBULANCE_EN_ROUTE));
        assertTrue(EmergencyStatus.AMBULANCE_EN_ROUTE.canTransitionTo(EmergencyStatus.ARRIVED_AT_SCENE));
        assertTrue(EmergencyStatus.ARRIVED_AT_SCENE.canTransitionTo(EmergencyStatus.TRANSPORTING));
        assertTrue(EmergencyStatus.TRANSPORTING.canTransitionTo(EmergencyStatus.ARRIVED_AT_HOSPITAL));
        assertTrue(EmergencyStatus.ARRIVED_AT_HOSPITAL.canTransitionTo(EmergencyStatus.COMPLETED));

        assertFalse(EmergencyStatus.QUEUED.canTransitionTo(EmergencyStatus.COMPLETED));
        assertFalse(EmergencyStatus.REPORTED.canTransitionTo(EmergencyStatus.AMBULANCE_EN_ROUTE));
        assertFalse(EmergencyStatus.COMPLETED.canTransitionTo(EmergencyStatus.QUEUED));
        assertFalse(EmergencyStatus.CANCELLED.canTransitionTo(EmergencyStatus.ASSIGNED));
        assertFalse(EmergencyStatus.ASSIGNED.canTransitionTo(EmergencyStatus.ASSIGNED));
        assertTrue(EmergencyStatus.COMPLETED.isTerminal());
        assertTrue(EmergencyStatus.CANCELLED.isTerminal());
    }

    @Test
    @DisplayName("Severity ranks map to the required CRITICAL, SERIOUS, MINOR order")
    void mapsSeverityRanks() {
        assertEquals(1, Severity.CRITICAL.rank());
        assertEquals(2, Severity.SERIOUS.rank());
        assertEquals(3, Severity.MINOR.rank());
        assertTrue(Severity.CRITICAL.rank() < Severity.SERIOUS.rank());
        assertTrue(Severity.SERIOUS.rank() < Severity.MINOR.rank());
    }
}
