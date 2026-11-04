package org.jwcarman.noir.feedback;

/** Holdout results for one requirement: how many of its tagged tests passed. */
public record RequirementResult(String requirementId, int passed, int total) {}
