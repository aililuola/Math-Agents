package io.github.aililuola.mathproofmesh.api;

public enum ActivityStatus {
  RUNNING("running"),
  COMPLETED("completed"),
  INFO("info"),
  WARNING("warning"),
  FAILED("failed");

  private final String wireName;

  ActivityStatus(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
