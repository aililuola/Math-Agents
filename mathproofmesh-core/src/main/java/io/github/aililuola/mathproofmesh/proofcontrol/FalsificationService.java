package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles and classifies bounded falsification without promoting proof. */
public final class FalsificationService {
  private static final Pattern BOUNDED_INTEGER =
      Pattern.compile(
          "^check\\s+([A-Za-z][A-Za-z0-9_]*)\\s+in\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]"
              + "\\s*:\\s*(.+?)\\s*(>=|<=|==|!=|>|<)\\s*(.+)$",
          Pattern.CASE_INSENSITIVE);

  public record Contract(
      String id,
      String targetSubjectId,
      List<Map<String, Object>> parameters,
      Map<String, Map<String, Integer>> finiteDomains,
      Map<String, String> exactRelation,
      String registeredHandler,
      int maxCases,
      String expectedIfFound,
      String expectedIfNotFound,
      ProofControlModels.FalsificationCompilationStatus status,
      String reason) {
    public Contract {
      parameters = List.copyOf(parameters);
      finiteDomains = Map.copyOf(finiteDomains);
      exactRelation = Map.copyOf(exactRelation);
      registeredHandler = ProofControlModels.blankToNull(registeredHandler);
    }
  }

  public record FastLaneContext(
      String targetClaimId,
      String targetObligationId,
      int requestedCases,
      double requestedRuntimeSeconds,
      boolean exactArithmetic,
      boolean broadSearch) {}

  public record FastLaneDecision(
      boolean eligible,
      boolean bypassesSoftMetaReview,
      boolean bypassesFactGate,
      boolean bypassesSandbox,
      String ruleId,
      String reason) {}

  public record ResultPolicy(
      String memoryTier,
      String messageType,
      boolean conclusiveRefutation,
      boolean claimStatusChanged,
      boolean factPromotionAllowed) {}

  public Contract compile(String request, String targetSubjectId, int maxCases) {
    String normalized = ProofIdentity.normalizeText(request);
    Matcher matcher = BOUNDED_INTEGER.matcher(normalized);
    ProofControlModels.FalsificationCompilationStatus status;
    List<Map<String, Object>> parameters = List.of();
    Map<String, Map<String, Integer>> domains = Map.of();
    Map<String, String> relation = Map.of();
    String handler = null;
    String reason;
    int boundedCases = maxCases <= 0 ? 256 : maxCases;
    if (matcher.matches()) {
      int minimum = Integer.parseInt(matcher.group(2));
      int maximum = Integer.parseInt(matcher.group(3));
      if (maximum < minimum) {
        status = ProofControlModels.FalsificationCompilationStatus.NEEDS_REWRITE;
        reason = "finite domain has reversed bounds";
      } else {
        String variable = matcher.group(1);
        long cases = (long) maximum - minimum + 1L;
        if (cases > boundedCases) {
          status = ProofControlModels.FalsificationCompilationStatus.NEEDS_REWRITE;
          reason = "finite domain exceeds max_cases";
        } else {
          status = ProofControlModels.FalsificationCompilationStatus.EXECUTABLE;
          parameters =
              List.of(Map.of("name", variable, "type", "integer"));
          domains =
              Map.of(variable, Map.of("min", minimum, "max", maximum));
          relation =
              Map.of(
                  "left", matcher.group(4).strip(),
                  "relation", relationName(matcher.group(5)),
                  "right", matcher.group(6).strip());
          handler = "bounded_integer_search";
          reason = "compiled exact bounded integer predicate";
        }
      }
    } else {
      status = ProofControlModels.FalsificationCompilationStatus.NON_AUTOMATABLE;
      reason = "request has no registered exact finite contract";
    }
    String id =
        "falsification_contract_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "target", targetSubjectId,
                        "request", normalized,
                        "max_cases", boundedCases))
                .substring(0, 20);
    return new Contract(
        id,
        ProofControlModels.required(targetSubjectId, "targetSubjectId"),
        parameters,
        domains,
        relation,
        handler,
        boundedCases,
        "record and independently replay the first counterexample",
        "keep the universal claim unverified",
        status,
        reason);
  }

  public FastLaneDecision fastLane(
      Contract contract,
      FastLaneContext context,
      int maximumCases,
      double maximumRuntimeSeconds) {
    boolean explicitTarget =
        context.targetClaimId() != null && !context.targetClaimId().isBlank()
            || context.targetObligationId() != null
                && !context.targetObligationId().isBlank();
    boolean eligible =
        contract.status() == ProofControlModels.FalsificationCompilationStatus.EXECUTABLE
            && explicitTarget
            && context.requestedCases() <= maximumCases
            && context.requestedRuntimeSeconds() <= maximumRuntimeSeconds
            && context.exactArithmetic()
            && !context.broadSearch();
    return new FastLaneDecision(
        eligible,
        eligible,
        false,
        false,
        eligible ? "fast_path.proof_control_falsification" : "budget.path_soft_limit",
        eligible
            ? "exact targeted falsification bypasses soft review only"
            : "missing target, unsupported contract, or resource cap");
  }

  public ResultPolicy classify(ProofControlModels.FalsificationOutcome outcome) {
    return switch (outcome) {
      case COUNTEREXAMPLE_FOUND ->
          new ResultPolicy(
              "negative", "counterexample", true, false, false);
      case NOT_REFUTED_BOUNDED ->
          new ResultPolicy(
              "insight", "computation_result", false, false, false);
      case INCONCLUSIVE, BLOCKED ->
          new ResultPolicy(
              "insight", "diagnostic", false, false, false);
    };
  }

  private static String relationName(String operator) {
    return switch (operator) {
      case ">=" -> "ge";
      case "<=" -> "le";
      case "==" -> "eq";
      case "!=" -> "ne";
      case ">" -> "gt";
      case "<" -> "lt";
      default -> throw new IllegalArgumentException("unsupported relation: " + operator);
    };
  }
}
