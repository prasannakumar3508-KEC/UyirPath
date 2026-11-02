package com.arunai.dispatch.model;

import java.util.ArrayList;
import java.util.List;

/** One of the five demonstration ambulances. */
public class Ambulance {
    private String id;
    private String baseLocation;
    private double baseLat;
    private double baseLon;
    private String currentLocation;
    private double currentLat;
    private double currentLon;
    private AmbulanceStatus status;
    private String assignedEmergencyId;
    private String destination;
    private Integer etaSeconds;
    private String tripStatus;
    private String updatedAt;

    public Ambulance() {}

    public Ambulance(String id, String baseLocation, double baseLat, double baseLon,
                     String currentLocation, double currentLat, double currentLon,
                     AmbulanceStatus status, String updatedAt) {
        this.id = id;
        this.baseLocation = baseLocation;
        this.baseLat = baseLat;
        this.baseLon = baseLon;
        this.currentLocation = currentLocation;
        this.currentLat = currentLat;
        this.currentLon = currentLon;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBaseLocation() { return baseLocation; }
    public void setBaseLocation(String baseLocation) { this.baseLocation = baseLocation; }
    public double getBaseLat() { return baseLat; }
    public void setBaseLat(double baseLat) { this.baseLat = baseLat; }
    public double getBaseLon() { return baseLon; }
    public void setBaseLon(double baseLon) { this.baseLon = baseLon; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public double getCurrentLat() { return currentLat; }
    public void setCurrentLat(double currentLat) { this.currentLat = currentLat; }
    public double getCurrentLon() { return currentLon; }
    public void setCurrentLon(double currentLon) { this.currentLon = currentLon; }
    public AmbulanceStatus getStatus() { return status; }
    public void setStatus(AmbulanceStatus status) { this.status = status; }
    public String getAssignedEmergencyId() { return assignedEmergencyId; }
    public void setAssignedEmergencyId(String assignedEmergencyId) { this.assignedEmergencyId = assignedEmergencyId; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public Integer getEtaSeconds() { return etaSeconds; }
    public void setEtaSeconds(Integer etaSeconds) { this.etaSeconds = etaSeconds; }
    public String getTripStatus() { return tripStatus; }
    public void setTripStatus(String tripStatus) { this.tripStatus = tripStatus; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    /** Convenience view used by the API. */
    public List<String> flags() {
        List<String> flags = new ArrayList<>();
        if (status == AmbulanceStatus.OUT_OF_SERVICE) flags.add("OUT_OF_SERVICE");
        return flags;
    }
}
