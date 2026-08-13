package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.SemanticInvariantAudit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically audits the scope and conclusion structure of a frozen root goal. */
@SuppressFBWarnings(
    value = {"IMPROPER_UNICODE", "REDOS"},
    justification =
        "Problem text is contract-bounded; NFKC and Locale.ROOT normalization precede the "
            + "bilingual quantifier and formula recognizers")
public final class ExactGoalContractChecker {
  private static final String VARIABLE =
      "[A-Za-z](?:_[A-Za-z0-9]+)?"
          + "(?:\\s*\\(\\s*[A-Za-z](?:_[A-Za-z0-9]+)?\\s*\\))?";
  private static final String VARIABLE_LIST =
      VARIABLE + "(?:\\s*(?:,|and|&|\\u548c|\\u3001)\\s*" + VARIABLE + ")*";
  private static final String OPTIONAL_INTEGER_DOMAIN =
      "(?:\\s+(?:a\\s+)?(?:fixed\\s+)?"
          + "(?:(?:positive|natural|nonnegative)\\s+)?integers?)?";
  private static final String OPTIONAL_INDEX_DOMAIN =
      "(?:sufficiently\\s+large\\s+)?(?:fixed\\s+)?"
          + "(?:(?:positive|natural|nonnegative)\\s+)?(?:integers?|numbers?)?\\s*";

  private static final Pattern ENGLISH_EXISTS =
      Pattern.compile(
          "\\bthere\\s+exist(?:s)?"
              + OPTIONAL_INTEGER_DOMAIN
              + "\\s+(?<variables>"
              + VARIABLE_LIST
              + ")",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ENGLISH_FORALL =
      Pattern.compile(
          "\\b(?:for\\s+(?:every|all|each)|each)\\s+"
              + OPTIONAL_INDEX_DOMAIN
              + "(?<variables>"
              + VARIABLE_LIST
              + ")",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ENGLISH_CHOOSE =
      Pattern.compile(
          "\\bchoose\\s+(?<variables>" + VARIABLE_LIST + ")",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CHINESE_EXISTS =
      Pattern.compile(
          "\\u5b58\\u5728(?:\\s*(?:\\u56fa\\u5b9a)?"
              + "(?:\\u6b63\\u6574\\u6570|\\u975e\\u8d1f\\u6574\\u6570|"
              + "\\u6574\\u6570|\\u81ea\\u7136\\u6570))?\\s*"
              + "(?<variables>"
              + VARIABLE_LIST
              + ")");
  private static final Pattern CHINESE_FORALL =
      Pattern.compile(
          "(?:(?:\\u5bf9\\u4e8e|\\u5bf9)\\s*)?"
              + "(?:\\u6bcf\\u4e00\\u4e2a|\\u6bcf\\u4e2a|\\u6240\\u6709|"
              + "\\u4efb\\u610f)\\s*"
              + "(?:(?:\\u6b63\\u6574\\u6570|\\u975e\\u8d1f\\u6574\\u6570|"
              + "\\u6574\\u6570|\\u81ea\\u7136\\u6570)\\s*)?"
              + "(?<variables>"
              + VARIABLE_LIST
              + ")");
  private static final Pattern SYMBOL_EXISTS =
      Pattern.compile("(?:\\\\exists|\\u2203)\\s*(?<variables>" + VARIABLE_LIST + ")");
  private static final Pattern SYMBOL_FORALL =
      Pattern.compile("(?:\\\\forall|\\u2200)\\s*(?<variables>" + VARIABLE_LIST + ")");
  private static final Pattern VARIABLE_TOKEN =
      Pattern.compile(
          "(?<![A-Za-z0-9_])(?<variable>[A-Za-z](?:_[A-Za-z0-9]+)?)"
              + "(?:\\s*\\(\\s*[A-Za-z](?:_[A-Za-z0-9]+)?\\s*\\))?"
              + "(?![A-Za-z0-9_])");

  private static final String FORMULA_TERM =
      "(?:[A-Za-z](?:_[A-Za-z0-9]+)?(?:\\([A-Za-z](?:_[A-Za-z0-9]+)?\\))?|\\d+)";
  private static final Pattern TRANSLATION_EQUATION =
      Pattern.compile(
          "(?<leftSequence>[A-Za-z][A-Za-z0-9]*)_\\{?"
              + "(?<leftIndex>[A-Za-z][A-Za-z0-9]*)\\+(?<shift>"
              + FORMULA_TERM
              + ")\\}?=(?<rightSequence>[A-Za-z][A-Za-z0-9]*)_\\{?"
              + "(?<rightIndex>[A-Za-z][A-Za-z0-9]*)\\}?\\+(?<offset>"
              + FORMULA_TERM
              + ")",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DIFFERENCE_EQUATION =
      Pattern.compile(
          "(?<leftSequence>[A-Za-z][A-Za-z0-9]*)_\\{?"
              + "(?<leftIndex>[A-Za-z][A-Za-z0-9]*)\\+1\\}?-"
              + "(?<rightSequence>[A-Za-z][A-Za-z0-9]*)_\\{?"
              + "(?<rightIndex>[A-Za-z][A-Za-z0-9]*)\\}?="
              + FORMULA_TERM,
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CONSTANT_DIFFERENCE_WORDING =
      Pattern.compile(
          "\\barithmetic\\s+progression\\b|"
              + "\\bconstant\\s+(?:first\\s+)?difference\\b|"
              + "\\bconstant\\s+gap\\b|"
              + "\\b(?:first\\s+)?difference\\s+(?:is|becomes)\\s+"
              + "(?:eventually\\s+)?constant\\b|"
              + "\\u7b49\\u5dee\\u6570\\u5217|"
              + "\\u516c\\u5dee(?:\\u6052\\u5b9a|\\u4e3a\\u5e38\\u6570|"
              + "\\u4e0d\\u53d8)|"
              + "(?:\\u4e00\\u9636)?\\u5dee\\u5206(?:\\u6700\\u7ec8)?"
              + "(?:\\u4e3a|\\u662f)?\\u5e38\\u6570",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TRANSLATION_WORDING =
      Pattern.compile(
          "\\b(?:index|shift|translation)\\s+periodicity\\b|"
              + "\\bperiodic\\s+up\\s+to\\s+(?:an\\s+)?additive\\s+translation\\b|"
              + "\\u5e73\\u79fb\\u5468\\u671f");
  private static final Pattern EXPLICIT_DEPENDENCE =
      Pattern.compile(
          "\\b(?:depending|depends)\\s+on\\b|\\bmay\\s+depend\\s+on\\b|"
              + "\\u4f9d\\u8d56\\u4e8e|\\u968f.{0,12}\\u53d8\\u5316",
          Pattern.CASE_INSENSITIVE);

  private final ScopeGuard scopeGuard;

  public ExactGoalContractChecker() {
    this(new ScopeGuard());
  }

  public ExactGoalContractChecker(ScopeGuard scopeGuard) {
    this.scopeGuard = Objects.requireNonNull(scopeGuard, "scopeGuard");
  }

  public enum ConclusionShape {
    INDEX_TRANSLATION_PERIODICITY,
    CONSTANT_FIRST_DIFFERENCE,
    MIXED_OR_CONTRADICTORY,
    UNKNOWN
  }

  public record QuantifierAtom(String kind, List<String> variables, int order) {
    public QuantifierAtom {
      kind = ProofControlModels.required(kind, "kind").toLowerCase(Locale.ROOT);
      if (!"exists".equals(kind) && !"forall".equals(kind)) {
        throw new IllegalArgumentException("unsupported quantifier kind: " + kind);
      }
      variables = variables == null ? List.of() : List.copyOf(variables);
      if (variables.isEmpty()) {
        throw new IllegalArgumentException("quantifier variables are required");
      }
      if (order < 0) {
        throw new IllegalArgumentException("order must be nonnegative");
      }
    }

    @Override
    public List<String> variables() {
      return List.copyOf(variables);
    }
  }

  public record GoalSignature(
      ProofControlModels.IndexScope indexScope,
      ProofControlModels.UniformityScope uniformityScope,
      List<QuantifierAtom> quantifierSkeleton,
      ConclusionShape conclusionShape,
      double confidence) {
    public GoalSignature {
      indexScope = Objects.requireNonNull(indexScope, "indexScope");
      uniformityScope = Objects.requireNonNull(uniformityScope, "uniformityScope");
      quantifierSkeleton =
          quantifierSkeleton == null ? List.of() : List.copyOf(quantifierSkeleton);
      conclusionShape = Objects.requireNonNull(conclusionShape, "conclusionShape");
      ProofControlModels.unit(confidence, "confidence");
    }

    @Override
    public List<QuantifierAtom> quantifierSkeleton() {
      return List.copyOf(quantifierSkeleton);
    }
  }

  public record AuditResult(
      GoalSignature source,
      GoalSignature candidate,
      List<SemanticInvariantAudit> findings) {
    public AuditResult {
      source = Objects.requireNonNull(source, "source");
      candidate = Objects.requireNonNull(candidate, "candidate");
      findings = findings == null ? List.of() : List.copyOf(findings);
    }

    @Override
    public List<SemanticInvariantAudit> findings() {
      return List.copyOf(findings);
    }

    public boolean passed() {
      return findings.stream().noneMatch(finding -> "fail".equals(finding.status()));
    }
  }

  public GoalSignature extract(String statement) {
    String text = normalize(statement);
    List<QuantifierAtom> skeleton = quantifierSkeleton(text);
    List<ProofControlModels.Quantifier> scopeQuantifiers =
        skeleton.stream()
            .map(
                atom ->
                    new ProofControlModels.Quantifier(
                        atom.kind(), String.join(",", atom.variables()), atom.order()))
            .toList();
    ProofControlModels.ScopeSignature scope =
        scopeGuard.extract("exact-goal", text, scopeQuantifiers, 0.0d);
    ProofControlModels.UniformityScope uniformity = scope.uniformity();
    if (witnessDependsOnInstance(text, skeleton)) {
      uniformity = ProofControlModels.UniformityScope.EXISTS_PER_INSTANCE;
    }
    ConclusionShape conclusion = conclusionShape(text);
    int detections = 0;
    detections += scope.indexScope() == ProofControlModels.IndexScope.UNKNOWN ? 0 : 1;
    detections += uniformity == ProofControlModels.UniformityScope.UNKNOWN ? 0 : 1;
    detections += skeleton.isEmpty() ? 0 : 1;
    detections += conclusion == ConclusionShape.UNKNOWN ? 0 : 1;
    return new GoalSignature(
        scope.indexScope(), uniformity, skeleton, conclusion, detections / 4.0d);
  }

  public AuditResult audit(String source, String candidate) {
    return audit(extract(source), candidate);
  }

  public AuditResult audit(GoalSignature source, String candidate) {
    GoalSignature frozenSource = Objects.requireNonNull(source, "source");
    GoalSignature target = extract(candidate);
    List<SemanticInvariantAudit> findings = new ArrayList<>();
    if (frozenSource.conclusionShape() == ConclusionShape.UNKNOWN) {
      findings.add(notApplicable("index_scope", frozenSource, target));
      findings.add(notApplicable("quantifier_skeleton", frozenSource, target));
      findings.add(notApplicable("uniform_witness_scope", frozenSource, target));
      findings.add(notApplicable("conclusion_shape", frozenSource, target));
      return new AuditResult(frozenSource, target, findings);
    }
    findings.add(indexScopeAudit(frozenSource, target));
    findings.add(quantifierAudit(frozenSource, target));
    findings.add(uniformityAudit(frozenSource, target));
    findings.add(conclusionAudit(frozenSource, target));
    return new AuditResult(frozenSource, target, findings);
  }

  private static SemanticInvariantAudit indexScopeAudit(
      GoalSignature source, GoalSignature target) {
    if (source.indexScope() == ProofControlModels.IndexScope.UNKNOWN) {
      return notApplicable("index_scope", source, target);
    }
    if (target.indexScope() == ProofControlModels.IndexScope.UNKNOWN) {
      return finding(
          "RECOGNIZED_SOURCE_UNPARSEABLE_TARGET: target index scope is UNKNOWN",
          "index_scope",
          List.of(source.indexScope().name()),
          "fail",
          List.of(target.indexScope().name()));
    }
    boolean passed = source.indexScope() == target.indexScope();
    return finding(
        passed
            ? "index scope agrees"
            : "INDEX_SCOPE_MISMATCH: source is "
                + source.indexScope().name()
                + " but target is "
                + target.indexScope().name(),
        "index_scope",
        List.of(source.indexScope().name()),
        passed ? "pass" : "fail",
        List.of(target.indexScope().name()));
  }

  private static SemanticInvariantAudit quantifierAudit(
      GoalSignature source, GoalSignature target) {
    if (source.quantifierSkeleton().isEmpty()) {
      return notApplicable("quantifier_skeleton", source, target);
    }
    if (target.quantifierSkeleton().isEmpty()) {
      return finding(
          "RECOGNIZED_SOURCE_UNPARSEABLE_TARGET: target quantifier skeleton is empty",
          "quantifier_skeleton",
          skeletonValues(source.quantifierSkeleton()),
          "fail",
          List.of());
    }
    boolean passed = structurallyEqual(source.quantifierSkeleton(), target.quantifierSkeleton());
    return finding(
        passed
            ? "quantifier skeleton agrees up to variable renaming"
            : "QUANTIFIER_ORDER_MISMATCH: source is "
                + skeletonDescription(source.quantifierSkeleton())
                + " but target is "
                + skeletonDescription(target.quantifierSkeleton()),
        "quantifier_skeleton",
        skeletonValues(source.quantifierSkeleton()),
        passed ? "pass" : "fail",
        skeletonValues(target.quantifierSkeleton()));
  }

  private static SemanticInvariantAudit uniformityAudit(
      GoalSignature source, GoalSignature target) {
    if (source.uniformityScope() == ProofControlModels.UniformityScope.UNKNOWN) {
      return notApplicable("uniform_witness_scope", source, target);
    }
    if (target.uniformityScope() == ProofControlModels.UniformityScope.UNKNOWN) {
      return finding(
          "RECOGNIZED_SOURCE_UNPARSEABLE_TARGET: target witness scope is UNKNOWN",
          "uniform_witness_scope",
          List.of(source.uniformityScope().name()),
          "fail",
          List.of(target.uniformityScope().name()));
    }
    boolean passed = source.uniformityScope() == target.uniformityScope();
    return finding(
        passed
            ? "uniform witness scope agrees"
            : "UNIFORM_WITNESS_SCOPE_MISMATCH: source is "
                + source.uniformityScope().name()
                + " but target is "
                + target.uniformityScope().name(),
        "uniform_witness_scope",
        List.of(source.uniformityScope().name()),
        passed ? "pass" : "fail",
        List.of(target.uniformityScope().name()));
  }

  private static SemanticInvariantAudit conclusionAudit(
      GoalSignature source, GoalSignature target) {
    if (target.conclusionShape() == ConclusionShape.MIXED_OR_CONTRADICTORY) {
      return finding(
          "MIXED_CONCLUSION_INTERPRETATION: target combines translation periodicity with "
              + "constant first difference",
          "conclusion_shape",
          List.of(source.conclusionShape().name()),
          "fail",
          List.of(target.conclusionShape().name()));
    }
    if (target.conclusionShape() == ConclusionShape.UNKNOWN) {
      return finding(
          "RECOGNIZED_SOURCE_UNPARSEABLE_TARGET: target conclusion shape is UNKNOWN",
          "conclusion_shape",
          List.of(source.conclusionShape().name()),
          "fail",
          List.of(target.conclusionShape().name()));
    }
    boolean passed = source.conclusionShape() == target.conclusionShape();
    return finding(
        passed
            ? "conclusion shape agrees"
            : "CONCLUSION_SHAPE_MISMATCH: source is "
                + source.conclusionShape().name()
                + " but target is "
                + target.conclusionShape().name(),
        "conclusion_shape",
        List.of(source.conclusionShape().name()),
        passed ? "pass" : "fail",
        List.of(target.conclusionShape().name()));
  }

  private static SemanticInvariantAudit notApplicable(
      String invariant, GoalSignature source, GoalSignature target) {
    return finding(
        "source exact-goal structure is not applicable to " + invariant,
        invariant,
        values(invariant, source),
        "not_applicable",
        values(invariant, target));
  }

  private static List<String> values(String invariant, GoalSignature signature) {
    return switch (invariant) {
      case "index_scope" -> List.of(signature.indexScope().name());
      case "quantifier_skeleton" -> skeletonValues(signature.quantifierSkeleton());
      case "uniform_witness_scope" -> List.of(signature.uniformityScope().name());
      case "conclusion_shape" -> List.of(signature.conclusionShape().name());
      default -> List.of();
    };
  }

  private static SemanticInvariantAudit finding(
      String detail,
      String invariant,
      List<String> sourceValues,
      String status,
      List<String> targetValues) {
    return new SemanticInvariantAudit(
        detail, invariant, sourceValues, status, targetValues);
  }

  private static boolean structurallyEqual(
      List<QuantifierAtom> source, List<QuantifierAtom> target) {
    if (source.size() != target.size()) {
      return false;
    }
    for (int index = 0; index < source.size(); index++) {
      QuantifierAtom left = source.get(index);
      QuantifierAtom right = target.get(index);
      if (!left.kind().equals(right.kind())
          || left.variables().size() != right.variables().size()) {
        return false;
      }
    }
    return true;
  }

  private static List<String> skeletonValues(List<QuantifierAtom> skeleton) {
    return skeleton.stream()
        .map(atom -> atom.kind() + "(" + String.join(",", atom.variables()) + ")")
        .toList();
  }

  private static String skeletonDescription(List<QuantifierAtom> skeleton) {
    return String.join(" -> ", skeletonValues(skeleton));
  }

  private static List<QuantifierAtom> quantifierSkeleton(String text) {
    List<RawQuantifier> matches = new ArrayList<>();
    addQuantifiers(matches, text, ENGLISH_EXISTS, "exists");
    addQuantifiers(matches, text, ENGLISH_FORALL, "forall");
    addQuantifiers(matches, text, ENGLISH_CHOOSE, "exists");
    addQuantifiers(matches, text, CHINESE_EXISTS, "exists");
    addQuantifiers(matches, text, CHINESE_FORALL, "forall");
    addQuantifiers(matches, text, SYMBOL_EXISTS, "exists");
    addQuantifiers(matches, text, SYMBOL_FORALL, "forall");
    matches.sort(
        Comparator.comparingInt(RawQuantifier::start)
            .thenComparing(Comparator.comparingInt(RawQuantifier::end).reversed()));

    List<RawQuantifier> distinct = new ArrayList<>();
    for (RawQuantifier match : matches) {
      boolean overlaps =
          distinct.stream()
              .anyMatch(
                  existing ->
                      existing.kind().equals(match.kind())
                          && match.start() < existing.end()
                          && existing.start() < match.end());
      if (!overlaps) {
        distinct.add(match);
      }
    }

    List<QuantifierAtom> skeleton = new ArrayList<>();
    for (int order = 0; order < distinct.size(); order++) {
      RawQuantifier match = distinct.get(order);
      skeleton.add(new QuantifierAtom(match.kind(), match.variables(), order));
    }
    return List.copyOf(skeleton);
  }

  private static void addQuantifiers(
      List<RawQuantifier> output, String text, Pattern pattern, String kind) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      List<String> variables = variables(matcher.group("variables"));
      if (!variables.isEmpty()) {
        output.add(new RawQuantifier(kind, variables, matcher.start(), matcher.end()));
      }
    }
  }

  private static List<String> variables(String text) {
    LinkedHashSet<String> variables = new LinkedHashSet<>();
    Matcher matcher = VARIABLE_TOKEN.matcher(text);
    while (matcher.find()) {
      variables.add(matcher.group("variable"));
    }
    return List.copyOf(variables);
  }

  private static boolean witnessDependsOnInstance(
      String text, List<QuantifierAtom> skeleton) {
    if (EXPLICIT_DEPENDENCE.matcher(text).find()) {
      return true;
    }
    List<String> witnesses =
        skeleton.stream()
            .filter(atom -> "exists".equals(atom.kind()))
            .flatMap(atom -> atom.variables().stream())
            .toList();
    List<String> instances =
        skeleton.stream()
            .filter(atom -> "forall".equals(atom.kind()))
            .flatMap(atom -> atom.variables().stream())
            .toList();
    for (String witness : witnesses) {
      for (String instance : instances) {
        Pattern dependency =
            Pattern.compile(
                "(?<![A-Za-z0-9_])"
                    + Pattern.quote(witness)
                    + "\\s*\\(\\s*"
                    + Pattern.quote(instance)
                    + "\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        if (dependency.matcher(text).find()) {
          return true;
        }
      }
    }
    return false;
  }

  private static ConclusionShape conclusionShape(String text) {
    String formula =
        text.replace("\\left", "")
            .replace("\\right", "")
            .replaceAll("\\s+", "");
    boolean translation = TRANSLATION_WORDING.matcher(text).find();
    boolean constantDifference = CONSTANT_DIFFERENCE_WORDING.matcher(text).find();

    Matcher translationMatcher = TRANSLATION_EQUATION.matcher(formula);
    while (translationMatcher.find()) {
      if (!translationMatcher.group("leftSequence")
              .equalsIgnoreCase(translationMatcher.group("rightSequence"))
          || !translationMatcher.group("leftIndex")
              .equalsIgnoreCase(translationMatcher.group("rightIndex"))) {
        continue;
      }
      if ("1".equals(translationMatcher.group("shift"))) {
        constantDifference = true;
      } else {
        translation = true;
      }
    }

    Matcher differenceMatcher = DIFFERENCE_EQUATION.matcher(formula);
    while (differenceMatcher.find()) {
      if (differenceMatcher.group("leftSequence")
              .equalsIgnoreCase(differenceMatcher.group("rightSequence"))
          && differenceMatcher.group("leftIndex")
              .equalsIgnoreCase(differenceMatcher.group("rightIndex"))) {
        constantDifference = true;
      }
    }

    if (translation && constantDifference) {
      return ConclusionShape.MIXED_OR_CONTRADICTORY;
    }
    if (translation) {
      return ConclusionShape.INDEX_TRANSLATION_PERIODICITY;
    }
    if (constantDifference) {
      return ConclusionShape.CONSTANT_FIRST_DIFFERENCE;
    }
    return ConclusionShape.UNKNOWN;
  }

  private static String normalize(String statement) {
    String value = ProofControlModels.required(statement, "statement");
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  private record RawQuantifier(
      String kind, List<String> variables, int start, int end) {
    private RawQuantifier {
      kind = Objects.requireNonNull(kind, "kind");
      variables = List.copyOf(variables);
    }

    @Override
    public List<String> variables() {
      return List.copyOf(variables);
    }
  }
}
