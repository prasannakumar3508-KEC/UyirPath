package com.uyirpath.dispatch.model;

/** Incident categories supported by the intake form. */
public enum IncidentType {
    ROAD_TRAFFIC_ACCIDENT("Road Traffic Accident"),
    CARDIAC_EMERGENCY("Cardiac Emergency"),
    GENERAL_MEDICAL("General Medical Emergency"),
    OTHER("Other Emergency");

    private final String label;

    IncidentType(String label) { this.label = label; }

    public String label() { return label; }

    public static IncidentType from(String value) {
        if (value == null) throw new IllegalArgumentException("Incident type is required");
        for (IncidentType t : values()) {
            if (t.name().equalsIgnoreCase(value) || t.label.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown incident type: " + value);
    }
}
