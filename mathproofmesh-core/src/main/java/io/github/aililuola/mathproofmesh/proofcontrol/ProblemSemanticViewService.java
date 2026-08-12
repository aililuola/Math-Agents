package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ProblemSemanticView;
import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import io.github.aililuola.mathproofmesh.contract.SemanticInvariantAudit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a non-authoritative, deterministically audited bilingual sidecar. */
@SuppressFBWarnings(
    value = {"IMPROPER_UNICODE", "REDOS"},
    justification =
        "Problem text is bounded by the strict ProblemContract before these linear token extractors; "
            + "NFKC and Locale.ROOT canonicalization precede invariant comparison")
public final class ProblemSemanticViewService {
  private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");
  private static final Pattern LATIN = Pattern.compile("\\b[A-Za-z]{2,}\\b");
  private static final Pattern MATH_BLOCK =
      Pattern.compile("\\$[^$]+\\$|\\\\\\([^)]*\\\\\\)|\\\\\\[[^]]*\\\\]");
  private static final Pattern LATEX =
      Pattern.compile("\\\\[A-Za-z]+(?:\\{[^{}]*})?");
  private static final Pattern IDENTIFIER =
      Pattern.compile(
          "(?<![A-Za-z0-9_\\\\])[A-Za-z](?:_[A-Za-z0-9{}]+|\\^[A-Za-z0-9{}]+)?"
              + "(?![A-Za-z0-9_])");
  private static final Pattern NUMBER =
      Pattern.compile("(?<![A-Za-z0-9_])\\d+(?:\\.\\d+)?");
  private static final Pattern SYMBOL =
      Pattern.compile("[∀∃≤≥≠∈∑∏∪∩→↔+\\-*/^]");

  private final SemanticProfileService profiles = new SemanticProfileService();

  public ProblemSemanticView build(
      String sourceStatement, ProblemSemanticViewCandidate candidate) {
    String source = ProofControlModels.required(sourceStatement, "sourceStatement");
    List<String> protectedFragments = protectedMathFragments(source);
    String compactTranslation = compact(candidate.englishStatement());
    List<String> missing =
        protectedFragments.stream()
            .filter(value -> !compactTranslation.contains(compact(value)))
            .toList();
    List<SemanticInvariantAudit> findings =
        profiles.audit(source, candidate.englishStatement());
    boolean flags =
        candidate.preservesHypotheses()
            && candidate.preservesQuantifiers()
            && candidate.preservesDomains()
            && candidate.preservesConclusion();
    boolean auditPassed =
        missing.isEmpty()
            && findings.stream().noneMatch(value -> "fail".equals(value.status()));
    boolean usable =
        CJK.matcher(source).find()
            && LATIN.matcher(candidate.englishStatement()).find()
            && flags
            && candidate.confidence() >= 0.75d
            && auditPassed;
    LinkedHashSet<String> notes = new LinkedHashSet<>(candidate.notes());
    if (!missing.isEmpty()) {
      notes.add("translation rejected because protected mathematical fragments changed");
    }
    if (!flags) {
      notes.add("translation rejected because a semantic preservation check failed");
    }
    findings.stream()
        .filter(value -> "fail".equals(value.status()))
        .forEach(
            value ->
                notes.add(
                    "deterministic semantic audit failed: "
                        + value.invariant()
                        + ": "
                        + value.detail()));
    return new ProblemSemanticView(
        findings,
        false,
        candidate.confidence(),
        auditPassed,
        candidate.englishStatement(),
        missing,
        List.copyOf(notes),
        protectedFragments,
        "zh",
        CanonicalJson.stableHash(source),
        usable ? "usable" : "rejected");
  }

  public static List<String> protectedMathFragments(String value) {
    String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
    LinkedHashSet<String> fragments = new LinkedHashSet<>();
    collect(fragments, MATH_BLOCK, text);
    collect(fragments, LATEX, text);
    collect(fragments, IDENTIFIER, text);
    collect(fragments, NUMBER, text);
    collect(fragments, SYMBOL, text);
    return List.copyOf(fragments);
  }

  static List<String> mathBlocks(String value) {
    List<String> blocks = new ArrayList<>();
    Matcher matcher = MATH_BLOCK.matcher(value);
    while (matcher.find()) {
      blocks.add(compact(matcher.group()));
    }
    return List.copyOf(blocks);
  }

  private static void collect(
      LinkedHashSet<String> fragments, Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      String fragment = matcher.group().strip();
      if (!fragment.isEmpty()) {
        fragments.add(fragment);
      }
    }
  }

  private static String compact(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "");
  }
}
