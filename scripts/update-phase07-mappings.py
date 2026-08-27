from __future__ import annotations

import csv
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def update_rows(
    relative_path: str,
    key_column: str,
    updates: dict[str, dict[str, str]],
) -> None:
    path = ROOT / relative_path
    with path.open("r", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative_path} has no header")
        fieldnames = reader.fieldnames
        rows = list(reader)

    matched: set[str] = set()
    for row in rows:
        replacement = updates.get(row[key_column])
        if replacement is None:
            continue
        row.update(replacement)
        matched.add(row[key_column])
    if matched != set(updates):
        raise RuntimeError(
            f"{relative_path}: missing rows {sorted(set(updates) - matched)}"
        )

    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(
            destination, fieldnames=fieldnames, lineterminator="\r\n"
        )
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    server = (
        "mathproofmesh-server/src/main/java/"
        "io/github/aililuola/mathproofmesh"
    )
    agent = f"{server}/agent"
    provider = f"{server}/provider"
    persistence = f"{server}/persistence"
    sources = {
        "src/mathproofmesh/agents.py": (
            f"{agent}/BudgetExhaustedError.java; "
            f"{agent}/StructuredOutputError.java; "
            f"{agent}/AgentProgressError.java; "
            f"{agent}/AgentCallWallTimeoutError.java; "
            f"{agent}/AgentStreamIdleTimeoutError.java; "
            f"{agent}/ReasoningOnlyStallError.java; "
            f"{agent}/ReasoningBudgetExhaustedError.java; "
            f"{provider}/AgentFailoverExhausted.java; "
            f"{provider}/UsageTotals.java; "
            f"{agent}/StructuredCallResult.java; "
            f"{agent}/CallLedger.java; "
            f"{agent}/StructuredAgentRunner.java; "
            f"{agent}/AgentGuardPolicies.java; "
            f"{agent}/ReviewIsolationPolicy.java"
        ),
        "src/mathproofmesh/llm/__init__.py": (
            f"{provider}/package-info.java; "
            f"{provider}/ProviderClientRegistry.java"
        ),
        "src/mathproofmesh/llm/anthropic.py": (
            f"{provider}/AnthropicClient.java; "
            f"{provider}/AnthropicProvider.java"
        ),
        "src/mathproofmesh/llm/base.py": (
            f"{provider}/LLMResponse.java; "
            f"{provider}/LlmProvider.java; "
            f"{provider}/LLMClient.java; "
            f"{provider}/AbstractHttpProvider.java; "
            f"{provider}/BoundedSseParser.java; "
            f"{provider}/JdkHttpTransport.java"
        ),
        "src/mathproofmesh/llm/deepseek.py": (
            f"{provider}/DeepSeekClient.java; "
            f"{provider}/DeepSeekProvider.java"
        ),
        "src/mathproofmesh/llm/gemini.py": (
            f"{provider}/GeminiClient.java; "
            f"{provider}/GeminiProvider.java"
        ),
        "src/mathproofmesh/llm/mock.py": (
            f"{provider}/MockClient.java; "
            f"{provider}/MockProvider.java"
        ),
        "src/mathproofmesh/llm/openai_compatible.py": (
            f"{provider}/OpenAICompatibleClient.java; "
            f"{provider}/OpenAiCompatibleProvider.java"
        ),
        "src/mathproofmesh/llm/pool.py": (
            f"{provider}/AgentCallFailure.java; "
            f"{provider}/ProviderCircuitOpenError.java; "
            f"{provider}/ProviderCircuitBreaker.java; "
            f"{provider}/SlidingWindowRateLimiter.java; "
            f"{provider}/AgentRuntime.java; "
            f"{provider}/AgentPool.java; "
            f"{provider}/ProviderCallRepository.java; "
            f"{provider}/ProviderCallRecord.java; "
            f"{provider}/InMemoryProviderCallRepository.java; "
            f"{persistence}/JdbcProviderCallRepository.java; "
            f"{persistence}/JdbcCircuitStateStore.java; "
            "mathproofmesh-server/src/main/resources/db/migration/"
            "V4__provider_call_runtime.sql"
        ),
        "src/mathproofmesh/prompts.py": (
            f"{agent}/PromptBundle.java; "
            f"{agent}/PromptFactory.java; "
            f"{agent}/PromptCatalog.java; "
            f"{agent}/PromptContextNormalizer.java; "
            f"{agent}/PromptJsonSchema.java; "
            f"{agent}/PromptRedactor.java; "
            f"{agent}/BoundedJsonRepairer.java"
        ),
    }
    source_evidence = (
        "DeepseekParityTest; ProviderAdapterFixtureTest; "
        "BoundedSseParserTest; AgentRuntimePolicyTest; "
        "StructuredAgentRunnerTest; GuardsAndContextParityTest; "
        "PoolParityTest; ReviewIsolationParityTest; "
        "TypedPromptSerializationParityTest; "
        "ProviderCallPostgresIT; Maven verify"
    )
    update_rows(
        "migration/source-state.csv",
        "source_file",
        {
            source: {
                "status": "migrated",
                "java_path": target,
                "verified_by": source_evidence,
                "notes": (
                    "Five direct JDK HttpClient/mock adapters, bounded SSE, "
                    "redacted structured-call artifacts, strict JSON and "
                    "representation-only repair, virtual-thread concurrency, "
                    "rate limiting, retry/failover, persistent circuit state, "
                    "provider_call idempotency, and usage reconciliation pass"
                ),
            }
            for source, target in sources.items()
        },
    )

    tests = (
        "mathproofmesh-server/src/test/java/"
        "io/github/aililuola/mathproofmesh"
    )
    test_targets = {
        "tests/test_deepseek.py": (
            f"{tests}/provider/DeepseekParityTest.java; "
            f"{tests}/provider/ProviderAdapterFixtureTest.java; "
            f"{tests}/provider/BoundedSseParserTest.java"
        ),
        "tests/test_guards_and_context.py": (
            f"{tests}/agent/GuardsAndContextParityTest.java; "
            f"{tests}/agent/StructuredAgentRunnerTest.java"
        ),
        "tests/test_pool.py": (
            f"{tests}/provider/PoolParityTest.java; "
            f"{tests}/agent/ReviewIsolationParityTest.java; "
            f"{tests}/provider/AgentRuntimePolicyTest.java"
        ),
        "tests/test_typed_prompt_serialization.py": (
            f"{tests}/agent/TypedPromptSerializationParityTest.java; "
            f"{tests}/agent/StructuredAgentRunnerTest.java"
        ),
    }
    update_rows(
        "migration/test-state.csv",
        "python_test_file",
        {
            source: {
                "status": "ported",
                "java_path": target,
                "verified_by": "Phase-07 parity tests; Maven verify",
                "notes": (
                    "Every declared Python test function is represented by a "
                    "same-semantic JUnit case, with additional HTTP fixture, "
                    "timeout, cancellation, persistence, and security coverage"
                ),
            }
            for source, target in test_targets.items()
        },
    )

    auxiliary = {
        "docs/DEEPSEEK_V4_PRO.md": (
            "docs/legacy/python-baseline/DEEPSEEK_V4_PRO.md; "
            "docs/providers.md"
        ),
        "docs/PROMPT_PROTOCOL.md": (
            "docs/legacy/python-baseline/PROMPT_PROTOCOL.md; "
            "docs/providers.md; docs/contracts.md"
        ),
    }
    update_rows(
        "migration/auxiliary-state.csv",
        "source_file",
        {
            source: {
                "status": "translated_verified",
                "java_path": target,
                "verified_by": (
                    "AuxiliaryFixtureIntegrityTest; phase-07 parity tests; "
                    "Maven verify"
                ),
                "notes": (
                    "Byte-exact legacy reference retained; direct provider, "
                    "prompt, redaction, retry, streaming, and operations "
                    "semantics are consolidated in Java documentation"
                ),
            }
            for source, target in auxiliary.items()
        },
    )


if __name__ == "__main__":
    main()
