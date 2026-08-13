package io.github.aililuola.mathproofmesh.research;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict, bounded parser for public research markers embedded in a reasoning trace. */
public final class ResearchCheckpointFrameParser {
  public static final String BEGIN_MARKER = "<MPM_PUBLIC_RESEARCH_CHECKPOINT_V1>";
  public static final String END_MARKER = "</MPM_PUBLIC_RESEARCH_CHECKPOINT_V1>";
  public static final int MAX_FRAME_BYTES = 16 * 1024;
  public static final int MAX_FINDINGS_PER_FRAME = 8;
  public static final int MAX_FRAMES_PER_CALL = 16;

  public List<ResearchCheckpointTraceSpan> parse(String trace) {
    String source = Objects.requireNonNullElse(trace, "");
    String traceHash = CanonicalJson.stableHash(source);
    List<ResearchCheckpointTraceSpan> spans = new ArrayList<>();
    Set<Integer> frameSequences = new HashSet<>();
    int cursor = 0;
    int frameCandidates = 0;
    while (cursor < source.length() && frameCandidates < MAX_FRAMES_PER_CALL) {
      MarkerLine begin = findLine(source, BEGIN_MARKER, cursor);
      if (begin == null) {
        break;
      }
      frameCandidates++;
      MarkerLine nextBegin = findLine(source, BEGIN_MARKER, begin.afterLineBreak());
      MarkerLine end = findLine(source, END_MARKER, begin.afterLineBreak());
      if (end == null || nextBegin != null && nextBegin.start() < end.start()) {
        cursor = nextBegin == null ? begin.afterLineBreak() : nextBegin.start();
        continue;
      }
      int jsonStart = begin.afterLineBreak();
      int jsonEnd = stripPrecedingCarriageReturn(source, end.start() - 1);
      if (jsonEnd < jsonStart) {
        cursor = end.afterMarker();
        continue;
      }
      String json = source.substring(jsonStart, jsonEnd);
      int markerEnd = end.afterMarker();
      cursor = Math.max(markerEnd, end.afterLineBreak());
      if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
        continue;
      }
      try {
        ResearchCheckpointFrame frame =
            ContractObjectMapper.read(json, ResearchCheckpointFrame.class);
        if (frame.findings().size() > MAX_FINDINGS_PER_FRAME
            || !frameSequences.add(frame.frameSequence())
            || !validExactQuotes(source, frame)) {
          continue;
        }
        spans.add(
            new ResearchCheckpointTraceSpan(
                frame,
                begin.start(),
                markerEnd,
                jsonStart,
                jsonEnd,
                CanonicalJson.stableHash(json),
                CanonicalJson.stableHash(source.substring(begin.start(), markerEnd)),
                traceHash,
                source.length()));
      } catch (RuntimeException ignored) {
        // A corrupt bounded frame cannot hide a later complete frame.
      }
    }
    return List.copyOf(spans);
  }

  private static boolean validExactQuotes(String trace, ResearchCheckpointFrame frame) {
    return frame.findings().stream()
        .allMatch(
            finding ->
                finding.sourceQuote() == null
                    || ResearchCheckpointTraceSpan.validatesExactQuote(
                        trace,
                        finding.quoteStart(),
                        finding.quoteEnd(),
                        finding.sourceQuote(),
                        finding.quoteSha256()));
  }

  public List<ResearchCheckpointTraceSpan> parse(String trace, String expectedTraceSha256) {
    String source = Objects.requireNonNullElse(trace, "");
    if (!hashesEqual(CanonicalJson.stableHash(source), required(expectedTraceSha256))) {
      return List.of();
    }
    return parse(source);
  }

  private static MarkerLine findLine(String source, String marker, int fromIndex) {
    int candidate = source.indexOf(marker, Math.max(0, fromIndex));
    while (candidate >= 0) {
      boolean lineStart = candidate == 0 || source.charAt(candidate - 1) == '\n';
      int afterMarker = candidate + marker.length();
      boolean lineEnd =
          afterMarker == source.length()
              || source.charAt(afterMarker) == '\n'
              || source.charAt(afterMarker) == '\r'
                  && afterMarker + 1 < source.length()
                  && source.charAt(afterMarker + 1) == '\n';
      if (lineStart && lineEnd) {
        int afterLineBreak = afterMarker;
        if (afterLineBreak < source.length() && source.charAt(afterLineBreak) == '\r') {
          afterLineBreak++;
        }
        if (afterLineBreak < source.length() && source.charAt(afterLineBreak) == '\n') {
          afterLineBreak++;
        }
        return new MarkerLine(candidate, afterMarker, afterLineBreak);
      }
      candidate = source.indexOf(marker, candidate + 1);
    }
    return null;
  }

  private static int stripPrecedingCarriageReturn(String source, int newlineIndex) {
    if (newlineIndex < 0 || source.charAt(newlineIndex) != '\n') {
      return -1;
    }
    int end = newlineIndex;
    if (end > 0 && source.charAt(end - 1) == '\r') {
      end--;
    }
    return end;
  }

  private static String required(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("expectedTraceSha256 is required");
    }
    return normalized;
  }

  static boolean hashesEqual(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private record MarkerLine(int start, int afterMarker, int afterLineBreak) {}
}
