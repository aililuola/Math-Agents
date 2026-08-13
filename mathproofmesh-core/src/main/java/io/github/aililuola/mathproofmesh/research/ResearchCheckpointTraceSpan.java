package io.github.aililuola.mathproofmesh.research;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import java.util.Objects;

/** Validated offsets and hashes for one public frame; raw provider reasoning is never retained. */
public record ResearchCheckpointTraceSpan(
    ResearchCheckpointFrame frame,
    int markerStart,
    int markerEnd,
    int jsonStart,
    int jsonEnd,
    String frameJsonSha256,
    String markerSha256,
    String traceSha256,
    int traceCharacters) {

  public ResearchCheckpointTraceSpan {
    frame = Objects.requireNonNull(frame, "frame");
    frameJsonSha256 = required(frameJsonSha256, "frameJsonSha256");
    markerSha256 = required(markerSha256, "markerSha256");
    traceSha256 = required(traceSha256, "traceSha256");
    if (markerStart < 0
        || jsonStart < markerStart
        || jsonEnd < jsonStart
        || markerEnd < jsonEnd
        || markerEnd > traceCharacters) {
      throw new IllegalArgumentException("invalid research checkpoint trace offsets");
    }
  }

  public boolean validatesAgainst(String trace) {
    String source = Objects.requireNonNull(trace, "trace");
    if (source.length() != traceCharacters
        || markerEnd > source.length()
        || !ResearchCheckpointFrameParser.hashesEqual(
            traceSha256, CanonicalJson.stableHash(source))) {
      return false;
    }
    return ResearchCheckpointFrameParser.hashesEqual(
            markerSha256, CanonicalJson.stableHash(source.substring(markerStart, markerEnd)))
        && ResearchCheckpointFrameParser.hashesEqual(
            frameJsonSha256, CanonicalJson.stableHash(source.substring(jsonStart, jsonEnd)));
  }

  public static boolean validatesExactQuote(
      String trace, int start, int end, String quote, String quoteSha256) {
    if (trace == null
        || quote == null
        || quoteSha256 == null
        || start < 0
        || end < start
        || end > trace.length()) {
      return false;
    }
    String exact = trace.substring(start, end);
    return exact.equals(quote)
        && ResearchCheckpointFrameParser.hashesEqual(
            CanonicalJson.stableHash(exact), quoteSha256);
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }
}
