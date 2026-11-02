package com.uyirpath.dispatch.model;

import java.util.ArrayList;
import java.util.List;

/** A hospital record. Bed numbers are explicitly DEMONSTRATION data. */
public class Hospital {
    private String id;
    private String name;
    private Double lat;
    private Double lon;
    private String address;
    private List<Facility> facilities = new ArrayList<>();
    private int totalBeds;
    private int occupiedBeds;
    private boolean operational = true;
    private String verificationStatus = "UNVERIFIED";
    private String osmRef;
    private String note;

    public Hospital() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLon() { return lon; }
    public void setLon(Double lon) { this.lon = lon; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public List<Facility> getFacilities() { return facilities; }
    public void setFacilities(List<Facility> facilities) { this.facilities = facilities; }
    public int getTotalBeds() { return totalBeds; }
    public void setTotalBeds(int totalBeds) { this.totalBeds = totalBeds; }
    public int getOccupiedBeds() { return occupiedBeds; }
    public void setOccupiedBeds(int occupiedBeds) { this.occupiedBeds = occupiedBeds; }
    public boolean isOperational() { return operational; }
    public void setOperational(boolean operational) { this.operational = operational; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getOsmRef() { return osmRef; }
    public void setOsmRef(String osmRef) { this.osmRef = osmRef; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public int getAvailableBeds() { return Math.max(0, totalBeds - occupiedBeds); }
    public boolean hasLocation() { return lat != null && lon != null; }
    public boolean supports(Facility facility) { return facilities != null && facilities.contains(facility); }
}
