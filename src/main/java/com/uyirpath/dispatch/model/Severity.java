package com.uyirpath.dispatch.model;

/** Clinical urgency. Lower rank = more urgent; the queue comparator uses this rank. */
public enum Severity {
    CRITICAL(1), SERIOUS(2), MINOR(3);

    private final int rank;

    Severity(int rank) { this.rank = rank; }

    public int rank() { return rank; }
}
