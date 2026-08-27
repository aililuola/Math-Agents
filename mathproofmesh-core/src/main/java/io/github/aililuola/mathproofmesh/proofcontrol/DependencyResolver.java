package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves explicit dependency namespaces and quarantines ambiguous legacy IDs. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Dependency namespaces are restricted ASCII identifiers and Locale.ROOT is used")
public final class DependencyResolver {
  public record NormalizationTask(
      String id,
      String sourceAttemptId,
      String sourceDeltaId,
      List<String> ambiguousIds,
      String status) {
    public NormalizationTask {
      ambiguousIds = List.copyOf(ambiguousIds);
    }
  }

  public record MigrationResult(
      List<ProofControlModels.DependencyRef> refs,
      String status,
      NormalizationTask task,
      boolean invalidatesClaim) {
    public MigrationResult {
      refs = List.copyOf(refs);
    }

    @Override
    public List<ProofControlModels.DependencyRef> refs() {
      return List.copyOf(refs);
    }
  }

  public record ResolutionContext(
      String attemptId,
      String deltaId,
      Set<String> localStepIds,
      Set<String> localClaimIds,
      Set<String> brokerFactIds,
      Set<String> messageIds,
      Set<String> obligationIds,
      Set<String> certificateIds,
      Set<String> externalResultIds,
      Set<String> invalidIds) {
    public ResolutionContext {
      attemptId = attemptId == null ? "" : attemptId.strip();
      deltaId = deltaId == null ? "" : deltaId.strip();
      localStepIds = copy(localStepIds);
      localClaimIds = copy(localClaimIds);
      brokerFactIds = copy(brokerFactIds);
      messageIds = copy(messageIds);
      obligationIds = copy(obligationIds);
      certificateIds = copy(certificateIds);
      externalResultIds = copy(externalResultIds);
      invalidIds = copy(invalidIds);
    }

    @Override
    public Set<String> localStepIds() {
      return Set.copyOf(localStepIds);
    }

    @Override
    public Set<String> localClaimIds() {
      return Set.copyOf(localClaimIds);
    }

    @Override
    public Set<String> brokerFactIds() {
      return Set.copyOf(brokerFactIds);
    }

    @Override
    public Set<String> messageIds() {
      return Set.copyOf(messageIds);
    }

    @Override
    public Set<String> obligationIds() {
      return Set.copyOf(obligationIds);
    }

    @Override
    public Set<String> certificateIds() {
      return Set.copyOf(certificateIds);
    }

    @Override
    public Set<String> externalResultIds() {
      return Set.copyOf(externalResultIds);
    }

    @Override
    public Set<String> invalidIds() {
      return Set.copyOf(invalidIds);
    }
  }

  public record Resolution(
      boolean resolved,
      List<ProofControlModels.DependencyRef> resolvedRefs,
      List<ProofControlModels.DependencyRef> missingRefs,
      List<ProofControlModels.DependencyRef> invalidRefs,
      List<ProofControlModels.DependencyRef> ambiguousRefs) {
    public Resolution {
      resolvedRefs = List.copyOf(resolvedRefs);
      missingRefs = List.copyOf(missingRefs);
      invalidRefs = List.copyOf(invalidRefs);
      ambiguousRefs = List.copyOf(ambiguousRefs);
    }
  }

  public MigrationResult migrateLegacy(
      List<String> dependencies,
      String sourceAttemptId,
      String sourceDeltaId,
      String sourceRouteId,
      Set<String> localSteps,
      Set<String> localClaims,
      Set<String> brokerFacts) {
    List<ProofControlModels.DependencyRef> refs = new ArrayList<>();
    List<String> ambiguous = new ArrayList<>();
    for (String raw : dependencies == null ? List.<String>of() : dependencies) {
      String value = ProofControlModels.required(raw, "legacy dependency");
      String lower = value.toLowerCase(Locale.ROOT);
      int delimiter = lower.indexOf(':');
      if (delimiter > 0) {
        String namespace = lower.substring(0, delimiter);
        String target = value.substring(delimiter + 1).strip();
        refs.add(
            new ProofControlModels.DependencyRef(
                parseKind(namespace),
                target,
                sourceAttemptId,
                sourceDeltaId,
                sourceRouteId,
                null,
                null));
      } else if (localSteps != null && localSteps.contains(value)) {
        refs.add(
            legacy(
                ProofControlModels.DependencyKind.LOCAL_STEP,
                value,
                sourceAttemptId,
                sourceDeltaId,
                sourceRouteId,
                "legacy_external_to_local_step"));
      } else if (localClaims != null && localClaims.contains(value)) {
        refs.add(
            legacy(
                ProofControlModels.DependencyKind.LOCAL_CLAIM,
                value,
                sourceAttemptId,
                sourceDeltaId,
                sourceRouteId,
                "legacy_external_to_local_claim"));
      } else if (brokerFacts != null && brokerFacts.contains(value)) {
        refs.add(
            legacy(
                ProofControlModels.DependencyKind.GLOBAL_FACT,
                value,
                sourceAttemptId,
                sourceDeltaId,
                sourceRouteId,
                "legacy_external_to_external_result"));
      } else {
        ambiguous.add(value);
      }
    }
    NormalizationTask task =
        ambiguous.isEmpty()
            ? null
            : new NormalizationTask(
                "dependency_normalization_"
                    + CanonicalJson.stableHash(
                            Map.of(
                                "attempt", nullToEmpty(sourceAttemptId),
                                "delta", nullToEmpty(sourceDeltaId),
                                "ambiguous", ambiguous.stream().sorted().toList()))
                        .substring(0, 20),
                sourceAttemptId,
                sourceDeltaId,
                ambiguous.stream().sorted().toList(),
                "open");
    return new MigrationResult(
        refs.stream()
            .distinct()
            .sorted(java.util.Comparator.comparing(ProofControlModels.DependencyRef::canonicalKey))
            .toList(),
        task == null ? "complete" : "ambiguous",
        task,
        false);
  }

  public Resolution resolve(
      List<ProofControlModels.DependencyRef> refs, ResolutionContext context) {
    List<ProofControlModels.DependencyRef> resolved = new ArrayList<>();
    List<ProofControlModels.DependencyRef> missing = new ArrayList<>();
    List<ProofControlModels.DependencyRef> invalid = new ArrayList<>();
    List<ProofControlModels.DependencyRef> ambiguous = new ArrayList<>();
    for (ProofControlModels.DependencyRef ref : refs) {
      if (context.invalidIds().contains(ref.targetId())) {
        invalid.add(ref);
        continue;
      }
      if (ref.migrationAudit() != null
          && ref.migrationAudit().contains("ambiguous")) {
        ambiguous.add(ref);
        continue;
      }
      boolean exists =
          switch (ref.kind()) {
            case LOCAL_STEP ->
                context.localStepIds().contains(ref.targetId())
                    && same(ref.sourceDeltaId(), context.deltaId());
            case LOCAL_CLAIM ->
                context.localClaimIds().contains(ref.targetId())
                    && same(ref.sourceAttemptId(), context.attemptId());
            case GLOBAL_FACT -> context.brokerFactIds().contains(ref.targetId());
            case MESSAGE -> context.messageIds().contains(ref.targetId());
            case OBLIGATION -> context.obligationIds().contains(ref.targetId());
            case TOOL_CERTIFICATE, FORMAL_CERTIFICATE ->
                context.certificateIds().contains(ref.targetId());
            case EXTERNAL_RESULT -> context.externalResultIds().contains(ref.targetId());
          };
      (exists ? resolved : missing).add(ref);
    }
    return new Resolution(
        missing.isEmpty() && invalid.isEmpty() && ambiguous.isEmpty(),
        resolved,
        missing,
        invalid,
        ambiguous);
  }

  public ProofControlModels.DependencyRef parseCanonical(String value) {
    String required = ProofControlModels.required(value, "dependency");
    int delimiter = required.indexOf(':');
    if (delimiter <= 0 || delimiter == required.length() - 1) {
      throw new IllegalArgumentException(
          "dependency reference must use an explicit namespace");
    }
    return new ProofControlModels.DependencyRef(
        parseKind(required.substring(0, delimiter)),
        required.substring(delimiter + 1),
        null,
        null,
        null,
        null,
        null);
  }

  private static ProofControlModels.DependencyRef legacy(
      ProofControlModels.DependencyKind kind,
      String target,
      String attempt,
      String delta,
      String route,
      String audit) {
    return new ProofControlModels.DependencyRef(
        kind, target, attempt, delta, route, null, audit);
  }

  private static ProofControlModels.DependencyKind parseKind(String raw) {
    String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "STEP", "LOCAL_STEP" -> ProofControlModels.DependencyKind.LOCAL_STEP;
      case "CLAIM", "LOCAL_CLAIM" -> ProofControlModels.DependencyKind.LOCAL_CLAIM;
      case "FACT", "GLOBAL_FACT" -> ProofControlModels.DependencyKind.GLOBAL_FACT;
      case "MESSAGE" -> ProofControlModels.DependencyKind.MESSAGE;
      case "OBLIGATION" -> ProofControlModels.DependencyKind.OBLIGATION;
      case "TOOL_CERTIFICATE" -> ProofControlModels.DependencyKind.TOOL_CERTIFICATE;
      case "FORMAL_CERTIFICATE" -> ProofControlModels.DependencyKind.FORMAL_CERTIFICATE;
      case "EXTERNAL_RESULT" -> ProofControlModels.DependencyKind.EXTERNAL_RESULT;
      case "EXTERNAL" ->
          throw new IllegalArgumentException(
              "legacy external requires contextual migration");
      default -> throw new IllegalArgumentException(
          "unknown dependency namespace: " + raw);
    };
  }

  private static boolean same(String declared, String current) {
    return declared == null || declared.isBlank() || declared.equals(current);
  }

  private static Set<String> copy(Set<String> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
