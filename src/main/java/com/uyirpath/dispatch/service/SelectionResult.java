package com.uyirpath.dispatch.service;

import java.util.List;

/** Result of the automatic ambulance + hospital matching step. */
public record SelectionResult(
        AmbulanceCandidate selectedAmbulance,
        HospitalCandidate selectedHospital,
        List<AmbulanceCandidate> ambulanceCandidates,
        List<HospitalCandidate> hospitalCandidates,
        String explanation,
        String failureReason) {

    public boolean success() { return selectedAmbulance != null && selectedHospital != null; }
}
