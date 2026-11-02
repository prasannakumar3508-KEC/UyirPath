package com.arunai.dispatch.model;

import java.util.List;

/** A dispatch record joining one emergency, one ambulance and one hospital. */
public class Dispatch {
    private String id;
    private String emergencyId;
    private String ambulanceId;
    private String hospitalId;
    private String assignedAt;
    private int ambulanceEtaSeconds;
    private int hospitalEtaSeconds;
    private double ambulanceDistanceMeters;
    private double hospitalDistanceMeters;
    private String status; // ACTIVE / COMPLETED / CANCELLED
    private String selectionReason;
    private String alternativesJson;
    private List<GeoPoint> ambulanceRoute;
    private List<GeoPoint> hospitalRoute;
    private List<GeoPoint> returnRoute;
    private TripPhase phase = TripPhase.TO_SCENE;
    private long phaseStartedAt;
    private double phaseDurationSeconds;
    private String routeWarning;
    private String completedAt;

    public Dispatch() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }
    public String getAmbulanceId() { return ambulanceId; }
    public void setAmbulanceId(String ambulanceId) { this.ambulanceId = ambulanceId; }
    public String getHospitalId() { return hospitalId; }
    public void setHospitalId(String hospitalId) { this.hospitalId = hospitalId; }
    public String getAssignedAt() { return assignedAt; }
    public void setAssignedAt(String assignedAt) { this.assignedAt = assignedAt; }
    public int getAmbulanceEtaSeconds() { return ambulanceEtaSeconds; }
    public void setAmbulanceEtaSeconds(int ambulanceEtaSeconds) { this.ambulanceEtaSeconds = ambulanceEtaSeconds; }
    public int getHospitalEtaSeconds() { return hospitalEtaSeconds; }
    public void setHospitalEtaSeconds(int hospitalEtaSeconds) { this.hospitalEtaSeconds = hospitalEtaSeconds; }
    public double getAmbulanceDistanceMeters() { return ambulanceDistanceMeters; }
    public void setAmbulanceDistanceMeters(double ambulanceDistanceMeters) { this.ambulanceDistanceMeters = ambulanceDistanceMeters; }
    public double getHospitalDistanceMeters() { return hospitalDistanceMeters; }
    public void setHospitalDistanceMeters(double hospitalDistanceMeters) { this.hospitalDistanceMeters = hospitalDistanceMeters; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSelectionReason() { return selectionReason; }
    public void setSelectionReason(String selectionReason) { this.selectionReason = selectionReason; }
    public String getAlternativesJson() { return alternativesJson; }
    public void setAlternativesJson(String alternativesJson) { this.alternativesJson = alternativesJson; }
    public List<GeoPoint> getAmbulanceRoute() { return ambulanceRoute; }
    public void setAmbulanceRoute(List<GeoPoint> ambulanceRoute) { this.ambulanceRoute = ambulanceRoute; }
    public List<GeoPoint> getHospitalRoute() { return hospitalRoute; }
    public void setHospitalRoute(List<GeoPoint> hospitalRoute) { this.hospitalRoute = hospitalRoute; }
    public List<GeoPoint> getReturnRoute() { return returnRoute; }
    public void setReturnRoute(List<GeoPoint> returnRoute) { this.returnRoute = returnRoute; }
    public TripPhase getPhase() { return phase; }
    public void setPhase(TripPhase phase) { this.phase = phase; }
    public long getPhaseStartedAt() { return phaseStartedAt; }
    public void setPhaseStartedAt(long phaseStartedAt) { this.phaseStartedAt = phaseStartedAt; }
    public double getPhaseDurationSeconds() { return phaseDurationSeconds; }
    public void setPhaseDurationSeconds(double phaseDurationSeconds) { this.phaseDurationSeconds = phaseDurationSeconds; }
    public String getRouteWarning() { return routeWarning; }
    public void setRouteWarning(String routeWarning) { this.routeWarning = routeWarning; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
}
