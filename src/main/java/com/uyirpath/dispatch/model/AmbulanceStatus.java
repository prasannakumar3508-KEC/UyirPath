package com.uyirpath.dispatch.model;

/** Ambulance operational state. Only AVAILABLE can receive an automatic assignment. */
public enum AmbulanceStatus {
    AVAILABLE,
    RESERVED,
    DISPATCHED,
    EN_ROUTE,
    AT_SCENE,
    TRANSPORTING,
    RETURNING,
    OUT_OF_SERVICE;

    public boolean isAvailable() { return this == AVAILABLE; }

    public static AmbulanceStatus from(String value) {
        if (value == null) throw new IllegalArgumentException("Ambulance status is required");
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown ambulance status: " + value);
        }
    }
}
