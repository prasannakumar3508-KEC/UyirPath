package com.uyirpath.dispatch.model;

/** Append-only audit entry for an emergency timeline. */
public record EmergencyEvent(long id, String emergencyId, String status, String note, String createdAt) {
}
