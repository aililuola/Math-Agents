package io.github.aililuola.mathproofmesh.api;

public enum ActivityImportance {
  MAJOR("major"),
  NORMAL("normal"),
  DETAIL("detail");

  private final String wireName;

  ActivityImportance(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
