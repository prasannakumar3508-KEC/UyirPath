package com.uyirpath.dispatch.model;

/** A registered emergency and its dispatch/lifecycle state. */
public class Emergency {
    private String id;
    private int seq;
    private IncidentType type;
    private Severity severity;
    private Facility requiredFacility;
    private double lat;
    private double lon;
    private String description;
    private EmergencyStatus status;
    private String createdAt;
    private String updatedAt;
    private String queuedAt;
    private String assignedAmbulanceId;
    private String assignedHospitalId;
    private Long snappedNodeId;
    private String dispatchNote;
    private String completedAt;

    public Emergency() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public IncidentType getType() { return type; }
    public void setType(IncidentType type) { this.type = type; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public Facility getRequiredFacility() { return requiredFacility; }
    public void setRequiredFacility(Facility requiredFacility) { this.requiredFacility = requiredFacility; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public EmergencyStatus getStatus() { return status; }
    public void setStatus(EmergencyStatus status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getQueuedAt() { return queuedAt; }
    public void setQueuedAt(String queuedAt) { this.queuedAt = queuedAt; }
    public String getAssignedAmbulanceId() { return assignedAmbulanceId; }
    public void setAssignedAmbulanceId(String assignedAmbulanceId) { this.assignedAmbulanceId = assignedAmbulanceId; }
    public String getAssignedHospitalId() { return assignedHospitalId; }
    public void setAssignedHospitalId(String assignedHospitalId) { this.assignedHospitalId = assignedHospitalId; }
    public Long getSnappedNodeId() { return snappedNodeId; }
    public void setSnappedNodeId(Long snappedNodeId) { this.snappedNodeId = snappedNodeId; }
    public String getDispatchNote() { return dispatchNote; }
    public void setDispatchNote(String dispatchNote) { this.dispatchNote = dispatchNote; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public int priority() { return severity == null ? 9 : severity.rank(); }
}
