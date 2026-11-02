package com.arunai.dispatch.service;

/** One hospital compared during automatic selection. */
public record HospitalCandidate(
        String hospitalId,
        String name,
        boolean operational,
        boolean hasLocation,
        boolean facilityMatch,
        int availableBeds,
        boolean routeFound,
        int etaSeconds,
        double distanceMeters,
        boolean eligible,
        String reason) {
}
