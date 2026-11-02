package com.arunai.dispatch.model;

/** Trip phase used by the demonstration movement simulator. */
public enum TripPhase {
    TO_SCENE,
    AT_SCENE,
    TO_HOSPITAL,
    AT_HOSPITAL,
    RETURNING,
    COMPLETED,
    ROUTE_BLOCKED
}
