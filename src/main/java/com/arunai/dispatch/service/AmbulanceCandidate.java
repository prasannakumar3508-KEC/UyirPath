package com.arunai.dispatch.service;

/** One ambulance compared during automatic selection. */
public record AmbulanceCandidate(
        String ambulanceId,
        String baseLocation,
        boolean available,
        boolean routeFound,
        int etaSeconds,
        double distanceMeters,
        String rankReason) {
}
