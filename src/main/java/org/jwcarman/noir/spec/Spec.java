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
    List<String> apiStubs) {

  public Spec {
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    apiStubs = apiStubs == null ? List.of() : List.copyOf(apiStubs);
  }

  public Spec withSchemaVersion(int v) {
    return new Spec(
        v, id, repo, baseSha, title, intent, requirements, constraints, qualityBar, apiStubs);
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
        apiStubs);
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
        apiStubs);
  }

  public Spec withBaseSha(String v) {
    return new Spec(
        schemaVersion, id, repo, v, title, intent, requirements, constraints, qualityBar, apiStubs);
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
        apiStubs);
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
        apiStubs);
  }

  public Spec withRequirements(List<Requirement> v) {
    return new Spec(
        schemaVersion, id, repo, baseSha, title, intent, v, constraints, qualityBar, apiStubs);
  }

  public Spec withConstraints(List<String> v) {
    return new Spec(
        schemaVersion, id, repo, baseSha, title, intent, requirements, v, qualityBar, apiStubs);
  }

  public Spec withQualityBar(QualityBar v) {
    return new Spec(
        schemaVersion, id, repo, baseSha, title, intent, requirements, constraints, v, apiStubs);
  }

  public Spec withApiStubs(List<String> v) {
    return new Spec(
        schemaVersion, id, repo, baseSha, title, intent, requirements, constraints, qualityBar, v);
  }
}
