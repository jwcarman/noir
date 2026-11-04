package org.jwcarman.noir.lifecycle;

/** Agent-outcome failure categories (spec §8.2). Each consumes retry budget. */
public enum Category {
    BUILD_FAILED,
    CONTRACT_VIOLATION,
    QUALITY_SHORTFALL,
    BEHAVIOR_FAILURE,
    NO_OUTPUT
}
