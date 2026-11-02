package com.uyirpath.dispatch.model;

/**
 * Emergency lifecycle. The enum owns the legal transition table so every
 * status change is validated in one place (used by the service and by tests).
 */
public enum EmergencyStatus {
    REPORTED,
    QUEUED,
    ASSIGNED,
    AMBULANCE_EN_ROUTE,
    ARRIVED_AT_SCENE,
    TRANSPORTING,
    ARRIVED_AT_HOSPITAL,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(EmergencyStatus next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case REPORTED -> next == QUEUED || next == CANCELLED;
            case QUEUED -> next == ASSIGNED || next == CANCELLED;
            case ASSIGNED -> next == AMBULANCE_EN_ROUTE || next == CANCELLED;
            case AMBULANCE_EN_ROUTE -> next == ARRIVED_AT_SCENE || next == CANCELLED;
            case ARRIVED_AT_SCENE -> next == TRANSPORTING || next == CANCELLED;
            case TRANSPORTING -> next == ARRIVED_AT_HOSPITAL || next == CANCELLED;
            case ARRIVED_AT_HOSPITAL -> next == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() { return this == COMPLETED || this == CANCELLED; }

    public static EmergencyStatus from(String value) {
        if (value == null) throw new IllegalArgumentException("Emergency status is required");
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown emergency status: " + value);
        }
    }
}
