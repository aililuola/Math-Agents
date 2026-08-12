package io.github.aililuola.mathproofmesh.proofcontrol;

import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationDomain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Four-state semantic admission gate for mathematical obligations. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "NFKC and Locale.ROOT canonicalization precede every semantic-quality comparison")
public final class SemanticQualityGate {
  public enum Verdict {
    ACCEPT,
    NEEDS_NORMALIZATION,
    SEARCH_OR_PROCESS_TASK,
    REJECT
  }

  public record Assessment(
      String obligationId,
      ObligationDomain domain,
      boolean truthApt,
      boolean hasObjects,
      boolean hasRelation,
      boolean hasScope,
      boolean placeholder,
      boolean selfImplication,
      boolean duplicatesMainGoal,
      boolean executable,
      double score,
      Verdict verdict,
      List<String> rejectionReasons,
      List<String> normalizationNeeds) {
    public Assessment {
      rejectionReasons = List.copyOf(rejectionReasons);
      normalizationNeeds = List.copyOf(normalizationNeeds);
    }

    public boolean accepted() {
      return verdict == Verdict.ACCEPT;
    }

    public boolean quarantined() {
      return verdict == Verdict.REJECT || verdict == Verdict.SEARCH_OR_PROCESS_TASK;
    }

    public boolean eligibleForCoreDebt() {
      return accepted();
    }

    public boolean eligibleForBottleneck() {
      return accepted();
    }
  }

  private static final Pattern RELATION =
      Pattern.compile(
          "(?:<=|>=|!=|==|=|<|>|≤|≥|≠|≡|∈|∉|⊆|⊂|⊇|⊃|→|⇒|↔|⇔|"
              + "\\b(?:is|are|equals|implies|iff|holds|exists|divides|belongs|"
              + "contains|preserves|satisfies)\\b|"
              + "等于|不等于|大于|小于|属于|包含|整除|互质|同余|成立|满足|"
              + "蕴含|当且仅当|收敛|发散|递增|递减|单调|有界|无界|存在)");
  private static final Pattern QUANTIFIER =
      Pattern.compile(
          "\\b(?:every|each|all|any|for all|for every|there exists|some|"
              + "eventually|sufficiently large|unique)\\b|"
              + "任意|所有|每个|存在|充分大|最终|给定|若|如果|假设|当且仅当");
  private static final Pattern ACTION =
      Pattern.compile(
          "^(?:find|search|explore|analyze|complete|avoid|choose|investigate|try|"
              + "寻找|找出|探索|尝试|分析|研究|枚举|搜索|避免|选择)\\b?");
  private static final Pattern IMPLICATION =
      Pattern.compile(
          "^(?:if\\s+)?(.+?)(?:\\s+then\\s+|\\s*(?:=>|->|→|⇒|implies|蕴含|"
              + "当且仅当|↔|⇔)\\s*)(.+?)[。.]*$",
          Pattern.CASE_INSENSITIVE);
  private static final List<String> PLACEHOLDERS =
      List.of(
          "find an invariant",
          "find a suitable invariant",
          "avoid circular dependencies",
          "prove the theorem",
          "complete the argument",
          "construct something suitable",
          "analyze carefully",
          "完成论证",
          "完成证明",
          "仔细分析",
          "证明该定理");
  private static final List<String> IMPLICIT_SCOPE =
      List.of(
          "increasing", "decreasing", "monotone", "bounded", "unbounded",
          "convergent", "periodic", "finite", "infinite",
          "递增", "递减", "单调", "有界", "无界", "收敛", "周期", "有限", "无限");

  private final DomainClassifier domains = new DomainClassifier();

  public Assessment assess(
      ProofControlModels.Obligation obligation,
      String sourceKind,
      ProofControlModels.Obligation mainGoal,
      String sourceStatement,
      String executableFirstStep) {
    String rawText =
        Normalizer.normalize(obligation.statement(), Normalizer.Form.NFKC)
            .strip()
            .toLowerCase(Locale.ROOT);
    String text =
        rawText
            .replaceFirst("^(?:prove|show|establish|verify)(?: that)?\\s+", "")
            .replaceFirst("^(?:求证|证明|试证明|请证明|证)[：:，,、\\s]*", "");
    boolean hasRelation = RELATION.matcher(text).find();
    boolean explicitScope = QUANTIFIER.matcher(text).find();
    boolean implicitScope = IMPLICIT_SCOPE.stream().anyMatch(text::contains);
    boolean hasScope = explicitScope || implicitScope || !obligation.assumptions().isEmpty();
    List<String> tokens =
        Pattern.compile("[\\p{L}\\p{N}_]+")
            .matcher(text)
            .results()
            .map(MatchResultAdapter::group)
            .filter(value -> value.length() > 1 || Character.isLetterOrDigit(value.charAt(0)))
            .toList();
    boolean hasObjects = tokens.stream().distinct().count() >= 2;
    boolean placeholder =
        PLACEHOLDERS.stream().anyMatch(value -> text.contains(value) || rawText.contains(value))
            || (ACTION.matcher(text).find() && !(hasRelation && hasScope));
    boolean truthApt = hasRelation && hasObjects && !placeholder;

    Matcher implication = IMPLICATION.matcher(text);
    boolean internalSelf =
        implication.matches()
            && ProofIdentity.obligationIdentityText(implication.group(1))
                .equals(ProofIdentity.obligationIdentityText(implication.group(2)));
    boolean sourceSelf =
        sourceStatement != null
            && !sourceStatement.isBlank()
            && ProofIdentity.obligationIdentityText(sourceStatement)
                .equals(ProofIdentity.obligationIdentityText(text));
    boolean selfImplication = internalSelf || sourceSelf;
    boolean duplicateGoal =
        mainGoal != null
            && obligation.kind() != ProofControlModels.ObligationKind.MAIN_GOAL
            && ProofIdentity.obligationIdentityText(text)
                .equals(ProofIdentity.obligationIdentityText(mainGoal.statement()));
    ObligationDomain domain =
        domains.classifyObligation(obligation, sourceKind).domain();
    if (domain == ObligationDomain.MATHEMATICAL && placeholder) {
      domain = ObligationDomain.SEARCH;
    }
    boolean executable =
        executableFirstStep != null && !executableFirstStep.isBlank()
            || truthApt && (hasScope
                || obligation.kind() == ProofControlModels.ObligationKind.CONSTRUCTION
                || obligation.kind()
                    == ProofControlModels.ObligationKind.COMPUTATION_QUESTION);

    List<String> structural =
        new java.util.ArrayList<>();
    if (!truthApt) {
      structural.add("not_truth_apt");
    }
    if (!hasObjects) {
      structural.add("missing_explicit_objects");
    }
    if (!hasRelation) {
      structural.add("missing_explicit_relation");
    }
    if (!hasScope) {
      structural.add("missing_quantifier_or_scope");
    }
    List<String> fatal = new java.util.ArrayList<>();
    if (domain != ObligationDomain.MATHEMATICAL) {
      fatal.add("non_mathematical_domain:" + domain.name().toLowerCase(Locale.ROOT));
    }
    if (placeholder) {
      fatal.add("placeholder");
    }
    if (selfImplication) {
      fatal.add("self_implication");
    }
    if (duplicateGoal) {
      fatal.add("duplicates_main_goal");
    }

    Verdict verdict;
    if (!fatal.isEmpty()) {
      verdict =
          switch (domain) {
            case SEARCH, PROCESS, TOOL, VERIFICATION -> Verdict.SEARCH_OR_PROCESS_TASK;
            default -> Verdict.REJECT;
          };
    } else if (tokens.isEmpty()) {
      verdict = Verdict.REJECT;
    } else if (truthApt && hasScope) {
      verdict = Verdict.ACCEPT;
    } else if (hasObjects || hasRelation) {
      verdict = Verdict.NEEDS_NORMALIZATION;
    } else {
      verdict = Verdict.REJECT;
    }
    List<String> rejection =
        verdict == Verdict.REJECT || verdict == Verdict.SEARCH_OR_PROCESS_TASK
            ? concat(fatal, structural)
            : List.of();
    List<String> normalization =
        verdict == Verdict.NEEDS_NORMALIZATION
            ? List.copyOf(structural)
            : verdict == Verdict.ACCEPT && implicitScope && !explicitScope
                ? List.of("explicit_index_quantifier")
                : List.of();
    int checks = 0;
    checks += domain == ObligationDomain.MATHEMATICAL ? 1 : 0;
    checks += truthApt ? 1 : 0;
    checks += hasObjects ? 1 : 0;
    checks += hasRelation ? 1 : 0;
    checks += hasScope ? 1 : 0;
    checks += executable ? 1 : 0;
    checks += placeholder ? 0 : 1;
    checks += selfImplication ? 0 : 1;
    checks += duplicateGoal ? 0 : 1;
    return new Assessment(
        obligation.id(),
        domain,
        truthApt,
        hasObjects,
        hasRelation,
        hasScope,
        placeholder,
        selfImplication,
        duplicateGoal,
        executable,
        checks / 9.0d,
        verdict,
        rejection,
        normalization);
  }

  public Assessment assessStatement(String statement) {
    String id =
        "semantic_"
            + io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(statement)
                .substring(0, 16);
    return assess(
        new ProofControlModels.Obligation(
            id,
            statement,
            ProofControlModels.ObligationKind.LEMMA,
            ProofControlModels.ObligationStatus.OPEN,
            List.of(),
            List.of(),
            1.0d,
            0.0d),
        null,
        null,
        null,
        null);
  }

  private static List<String> concat(List<String> left, List<String> right) {
    java.util.ArrayList<String> values = new java.util.ArrayList<>(left);
    values.addAll(right);
    return List.copyOf(values);
  }

  private static final class MatchResultAdapter {
    private MatchResultAdapter() {}

    static String group(java.util.regex.MatchResult result) {
      return result.group();
    }
  }
}
