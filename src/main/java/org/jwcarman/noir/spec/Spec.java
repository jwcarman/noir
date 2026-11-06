package org.jwcarman.noir.spec;

import java.util.List;

/** The visible half of a work item: the source of truth stored as {@code spec.json}. */
public record Spec(
    int schemaVersion,
    String id,
    String repo,
    String baseSha,
    String title,
    String intent,
    List<Requirement> requirements,
    List<String> constraints,
    QualityBar qualityBar,
    List<String> apiStubs,
    List<String> modifies,
    List<String> testsToUpdate) {

  public Spec {
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    apiStubs = apiStubs == null ? List.of() : List.copyOf(apiStubs);
    modifies = modifies == null ? List.of() : List.copyOf(modifies);
    testsToUpdate = testsToUpdate == null ? List.of() : List.copyOf(testsToUpdate);
  }

  public Spec withSchemaVersion(int v) {
    return new Spec(
        v,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withId(String v) {
    return new Spec(
        schemaVersion,
        v,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withRepo(String v) {
    return new Spec(
        schemaVersion,
        id,
        v,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withBaseSha(String v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        v,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withTitle(String v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        v,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withIntent(String v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        v,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withRequirements(List<Requirement> v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        intent,
        v,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withConstraints(List<String> v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        v,
        qualityBar,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withQualityBar(QualityBar v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        v,
        apiStubs,
        modifies,
        testsToUpdate);
  }

  public Spec withApiStubs(List<String> v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        v,
        modifies,
        testsToUpdate);
  }

  public Spec withModifies(List<String> v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        v,
        testsToUpdate);
  }

  public Spec withTestsToUpdate(List<String> v) {
    return new Spec(
        schemaVersion,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        apiStubs,
        modifies,
        v);
  }
}
