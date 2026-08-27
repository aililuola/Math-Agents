package io.github.aililuola.mathproofmesh.verification;

/** Feedback is machine-readable direction, never premise-eligible evidence. */
public record FeedbackDirective(
    String kind,
    String status,
    String source,
    boolean premiseEligible,
    String text) {

  public FeedbackDirective {
    kind = required(kind, "kind");
    status = required(status, "status");
    source = required(source, "source");
    text = normalize(required(text, "text"));
    if (premiseEligible) {
      throw new IllegalArgumentException("review feedback cannot be premise eligible");
    }
  }

  public static FeedbackDirective open(
      String kind, String source, String text) {
    return new FeedbackDirective(kind, "open", source, false, text);
  }

  public String tagged() {
    return "["
        + kind
        + "][STATUS:"
        + status
        + "][SOURCE:"
        + source
        + "][PREMISE_ELIGIBLE:false] "
        + text;
  }

  public String authorityInstruction() {
    return ("NON-AUTHORITATIVE REVIEW DIRECTIVE%n"
            + "{\"kind\":\"%s\",\"status\":\"%s\",\"source\":\"%s\","
            + "\"premise_eligible\":false,\"text\":\"%s\"}%n"
            + "This identifies work that still requires proof and must not "
            + "extend a verified checkpoint.")
        .formatted(kind, status, source, escape(text))
        .strip();
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  private static String normalize(String value) {
    return value.replaceAll("\\s+", " ").trim();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
