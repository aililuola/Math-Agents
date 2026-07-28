from __future__ import annotations

import math
import re
from typing import Any

import sympy as sp
from sympy.ntheory.modular import crt as sympy_crt

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence
from .symbolic import parse_expression


OPERATIONS = {
    "multiplicative_order",
    "crt",
    "p_adic_valuation",
    "primitive_root",
    "is_prime",
    "factorization",
}
FACTORIZATION_LIMIT = 10**12
PRIMALITY_LIMIT = 10**18
ORDER_MODULUS_LIMIT = 10**12
CRT_MODULUS_LIMIT = 10**12
MAX_CRT_CONGRUENCES = 64

# Deterministic Miller-Rabin base set, proven correct for n < 3.3 * 10**24,
# used as the pure-Python recheck independent of sympy.ntheory.
_MILLER_RABIN_BASES = (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37)

_FINITE_ONLY_CAVEAT = (
    "This certifies only the specific finite assertion checked; it cannot "
    "certify any infinite generalization of it."
)


def _exact_int(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and re.fullmatch(r"[+-]?\d+", value.strip()):
        return int(value)
    raise ValueError(f"{label} must be an integer")


def _miller_rabin_is_prime(n: int) -> tuple[bool, int | None]:
    """Pure-Python deterministic primality recheck; returns a witness base
    when compositeness is proven."""

    if n < 2:
        return False, None
    for base in _MILLER_RABIN_BASES:
        if n == base:
            return True, None
        if n % base == 0:
            return False, base
    d = n - 1
    two_exponent = 0
    while d % 2 == 0:
        d //= 2
        two_exponent += 1
    for base in _MILLER_RABIN_BASES:
        value = pow(base, d, n)
        if value in (1, n - 1):
            continue
        for _ in range(two_exponent - 1):
            value = value * value % n
            if value == n - 1:
                break
        else:
            return False, base
    return True, None


def _guard_inconclusive(reason: str, scope: dict[str, Any]) -> HandlerEvidence:
    return HandlerEvidence(
        outcome=ExperimentOutcome.INCONCLUSIVE,
        evidence_strength=EvidenceStrength.HEURISTIC,
        scope=scope,
        verification_notes=[
            reason + " The request is refused rather than risking an unverifiable "
            "long computation."
        ],
    )


def _certified(
    statement: str,
    certificate: dict[str, Any],
    *,
    cases_checked: int = 1,
    extra_notes: list[str] | None = None,
) -> HandlerEvidence:
    return HandlerEvidence(
        outcome=ExperimentOutcome.CERTIFIED,
        evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
        certificate=certificate,
        exact_arithmetic=True,
        cases_checked=cases_checked,
        independently_verified=True,
        verification_notes=[
            f"Exact finite computation: {statement}.",
            *(extra_notes or []),
            _FINITE_ONLY_CAVEAT,
        ],
    )


def _claim_refuted(
    statement: str,
    counterexample: dict[str, Any],
) -> HandlerEvidence:
    return HandlerEvidence(
        outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
        counterexample=counterexample,
        exact_arithmetic=True,
        cases_checked=1,
        independently_verified=True,
        verification_notes=[
            f"Exact finite computation refutes the claimed value: {statement}.",
            _FINITE_ONLY_CAVEAT,
        ],
    )


def _verify_order(a: int, n: int, order: int) -> bool:
    """Independent minimal-order recheck via the prime divisors of the order."""

    if order < 1 or pow(a, order, n) != 1:
        return False
    return all(pow(a, order // prime, n) != 1 for prime in sp.factorint(order))


def _run_multiplicative_order(args: dict[str, Any]) -> HandlerEvidence:
    a = _exact_int(args.get("a"), "a")
    n = _exact_int(args.get("n"), "n")
    if n < 2:
        raise ValueError("multiplicative_order requires n >= 2")
    if n > ORDER_MODULUS_LIMIT:
        return _guard_inconclusive(
            f"The modulus {n} exceeds the exact multiplicative-order guard "
            f"bound {ORDER_MODULUS_LIMIT}.",
            {"operation": "multiplicative_order", "n": n},
        )
    if math.gcd(a, n) != 1:
        raise ValueError("multiplicative_order requires gcd(a, n) = 1")
    order = int(sp.ntheory.n_order(a, n))
    if not _verify_order(a, n, order):
        raise RuntimeError(
            "the computed multiplicative order failed its independent recheck"
        )
    statement = f"ord_{n}({a % n}) = {order}"
    certificate = {
        "operation": "multiplicative_order",
        "a": a,
        "n": n,
        "order": order,
        "statement": statement,
    }
    claimed = args.get("claimed")
    if claimed is not None:
        claimed_order = _exact_int(claimed, "claimed")
        if claimed_order != order:
            return _claim_refuted(
                statement,
                {**certificate, "claimed_order": claimed_order},
            )
        certificate["claimed_order"] = claimed_order
    return _certified(
        statement,
        certificate,
        extra_notes=[
            "The order was independently rechecked with pure-integer modular "
            "exponentiation over the prime divisors of the computed order."
        ],
    )


def _run_crt(args: dict[str, Any]) -> HandlerEvidence:
    raw_residues = args.get("residues")
    raw_moduli = args.get("moduli")
    if not isinstance(raw_residues, list) or not raw_residues:
        raise ValueError("residues must be a non-empty list of integers")
    if not isinstance(raw_moduli, list) or not raw_moduli:
        raise ValueError("moduli must be a non-empty list of integers")
    if len(raw_residues) != len(raw_moduli):
        raise ValueError("residues and moduli must have the same length")
    if len(raw_moduli) > MAX_CRT_CONGRUENCES:
        raise ValueError(f"crt accepts at most {MAX_CRT_CONGRUENCES} congruences")
    residues = [
        _exact_int(value, f"residues[{index}]")
        for index, value in enumerate(raw_residues)
    ]
    moduli = [
        _exact_int(value, f"moduli[{index}]") for index, value in enumerate(raw_moduli)
    ]
    for index, modulus in enumerate(moduli):
        if modulus < 1:
            raise ValueError(f"moduli[{index}] must be a positive integer")
        if modulus > CRT_MODULUS_LIMIT:
            return _guard_inconclusive(
                f"moduli[{index}] = {modulus} exceeds the CRT guard bound "
                f"{CRT_MODULUS_LIMIT}.",
                {"operation": "crt", "modulus": modulus},
            )
    solution = sympy_crt(moduli, residues)
    if solution is None:
        witness = None
        for i in range(len(moduli)):
            for j in range(i + 1, len(moduli)):
                gcd_ij = math.gcd(moduli[i], moduli[j])
                if (residues[i] - residues[j]) % gcd_ij != 0:
                    witness = {
                        "index_pair": [i, j],
                        "moduli": [moduli[i], moduli[j]],
                        "residues": [residues[i], residues[j]],
                        "gcd": gcd_ij,
                    }
                    break
            if witness is not None:
                break
        if witness is None:
            return HandlerEvidence(
                outcome=ExperimentOutcome.INCONCLUSIVE,
                evidence_strength=EvidenceStrength.HEURISTIC,
                scope={"operation": "crt"},
                verification_notes=[
                    "SymPy reported the congruence system unsolvable but no "
                    "independent pairwise inconsistency witness was found; no "
                    "evidence is accepted."
                ],
            )
        statement = "the declared congruence system has no solution"
        return _certified(
            statement,
            {
                "operation": "crt",
                "residues": residues,
                "moduli": moduli,
                "solvable": False,
                "inconsistency_witness": witness,
                "statement": statement,
            },
            extra_notes=[
                "The unsolvability was independently rechecked through an "
                "explicit pairwise gcd inconsistency witness."
            ],
        )
    value, combined_modulus = int(solution[0]), int(solution[1])
    if not all(
        (value - residue) % modulus == 0 for residue, modulus in zip(residues, moduli)
    ):
        raise RuntimeError("the CRT solution failed its independent recheck")
    statement = f"x = {value} (mod {combined_modulus}) solves the declared system"
    certificate = {
        "operation": "crt",
        "residues": residues,
        "moduli": moduli,
        "solvable": True,
        "solution": value,
        "combined_modulus": combined_modulus,
        "statement": statement,
    }
    claimed = args.get("claimed")
    if claimed is not None:
        claimed_solution = _exact_int(claimed, "claimed")
        if claimed_solution % combined_modulus != value % combined_modulus:
            return _claim_refuted(
                statement,
                {**certificate, "claimed_solution": claimed_solution},
            )
        certificate["claimed_solution"] = claimed_solution
    return _certified(
        statement,
        certificate,
        cases_checked=len(moduli),
        extra_notes=[
            "Every declared congruence was independently rechecked against "
            "the solution with exact integer arithmetic."
        ],
    )


def _run_p_adic_valuation(args: dict[str, Any]) -> HandlerEvidence:
    p = _exact_int(args.get("p"), "p")
    if p < 2:
        raise ValueError("p must be a prime number")
    if p > PRIMALITY_LIMIT:
        return _guard_inconclusive(
            f"The prime candidate {p} exceeds the primality guard bound "
            f"{PRIMALITY_LIMIT}.",
            {"operation": "p_adic_valuation", "p": p},
        )
    p_is_prime, _ = _miller_rabin_is_prime(p)
    if not sp.isprime(p) or not p_is_prime:
        raise ValueError("p must be a prime number")
    expression = str(args.get("expression", ""))
    if not expression.strip():
        raise ValueError("expression must be a non-empty string")
    parsed = parse_expression(expression)
    raw_assignment = args.get("assignment", {})
    if not isinstance(raw_assignment, dict):
        raise ValueError("assignment must map variable names to integers")
    assignment = {
        str(name): _exact_int(value, f"assignment[{name!r}]")
        for name, value in raw_assignment.items()
    }
    free_names = {str(symbol) for symbol in parsed.free_symbols}
    missing = sorted(free_names - set(assignment))
    if missing:
        raise ValueError(
            "assignment must cover every expression variable; missing: "
            + ", ".join(missing)
        )
    substitution = {
        symbol: sp.Integer(assignment[str(symbol)]) for symbol in parsed.free_symbols
    }
    value_expr = sp.cancel(parsed.subs(substitution))
    if not value_expr.is_Integer:
        raise ValueError(
            "the expression must evaluate to an exact integer at the "
            "declared assignment"
        )
    value = int(value_expr)
    if value == 0:
        return HandlerEvidence(
            outcome=ExperimentOutcome.INCONCLUSIVE,
            evidence_strength=EvidenceStrength.HEURISTIC,
            scope={"operation": "p_adic_valuation", "p": p, "value": 0},
            verification_notes=[
                "The expression evaluates to 0, whose p-adic valuation is "
                "infinite; no finite certificate is produced."
            ],
        )
    valuation = int(sp.multiplicity(p, value))
    if value % p**valuation != 0 or value % p ** (valuation + 1) == 0:
        raise RuntimeError("the p-adic valuation failed its independent recheck")
    statement = f"v_{p}({expression} at {assignment or 'constants'}) = {valuation}"
    certificate = {
        "operation": "p_adic_valuation",
        "p": p,
        "expression": expression,
        "assignment": assignment,
        "value": value,
        "valuation": valuation,
        "statement": statement,
    }
    claimed = args.get("claimed")
    if claimed is not None:
        claimed_valuation = _exact_int(claimed, "claimed")
        if claimed_valuation != valuation:
            return _claim_refuted(
                statement,
                {**certificate, "claimed_valuation": claimed_valuation},
            )
        certificate["claimed_valuation"] = claimed_valuation
    return _certified(
        statement,
        certificate,
        extra_notes=[
            "Divisibility by p**valuation and non-divisibility by the next "
            "power were independently rechecked with exact integers."
        ],
    )


def _primitive_root_structure_allows(n: int) -> bool:
    """Exact structural test: primitive roots exist iff n in {1,2,4,p^k,2p^k}."""

    if n in (1, 2, 4):
        return True
    remainder = n // 2 if n % 2 == 0 else n
    if n % 4 == 0:
        return False
    factors = sp.factorint(remainder)
    return len(factors) == 1 and next(iter(factors)) % 2 == 1


def _run_primitive_root(args: dict[str, Any]) -> HandlerEvidence:
    n = _exact_int(args.get("n"), "n")
    if n < 2:
        raise ValueError("primitive_root requires n >= 2")
    if n > ORDER_MODULUS_LIMIT:
        return _guard_inconclusive(
            f"The modulus {n} exceeds the exact primitive-root guard bound "
            f"{ORDER_MODULUS_LIMIT}.",
            {"operation": "primitive_root", "n": n},
        )
    claimed = args.get("claimed")
    totient = int(sp.totient(n))
    if claimed is not None and not isinstance(claimed, bool):
        candidate = _exact_int(claimed, "claimed")
        is_root = math.gcd(candidate, n) == 1 and _verify_order(candidate, n, totient)
        statement = (
            f"{candidate} is a primitive root modulo {n}"
            if is_root
            else f"{candidate} is not a primitive root modulo {n}"
        )
        if not is_root:
            return _claim_refuted(
                statement,
                {
                    "operation": "primitive_root",
                    "n": n,
                    "claimed_root": candidate,
                    "totient": totient,
                },
            )
        return _certified(
            statement,
            {
                "operation": "primitive_root",
                "n": n,
                "claimed_root": candidate,
                "totient": totient,
                "statement": statement,
            },
            extra_notes=[
                "The claimed root's order was independently rechecked to equal "
                "the totient via its prime divisors."
            ],
        )
    root = sp.primitive_root(n)
    structure_allows = _primitive_root_structure_allows(n)
    if root is None:
        if structure_allows:
            return HandlerEvidence(
                outcome=ExperimentOutcome.INCONCLUSIVE,
                evidence_strength=EvidenceStrength.HEURISTIC,
                scope={"operation": "primitive_root", "n": n},
                verification_notes=[
                    "SymPy found no primitive root but the structural test "
                    "disagrees; no evidence is accepted."
                ],
            )
        statement = f"no primitive root exists modulo {n}"
        certificate = {
            "operation": "primitive_root",
            "n": n,
            "exists": False,
            "statement": statement,
        }
        if isinstance(claimed, bool) and claimed is True:
            return _claim_refuted(statement, certificate)
        return _certified(
            statement,
            certificate,
            extra_notes=[
                "Nonexistence was independently rechecked against the exact "
                "structure theorem (n must be 1, 2, 4, p^k, or 2p^k)."
            ],
        )
    root_value = int(root)
    if not structure_allows or not _verify_order(root_value, n, totient):
        raise RuntimeError("the primitive root failed its independent recheck")
    statement = f"{root_value} is a primitive root modulo {n}"
    certificate = {
        "operation": "primitive_root",
        "n": n,
        "exists": True,
        "primitive_root": root_value,
        "totient": totient,
        "statement": statement,
    }
    if isinstance(claimed, bool) and claimed is False:
        return _claim_refuted(statement, certificate)
    return _certified(
        statement,
        certificate,
        extra_notes=[
            "The root's order was independently rechecked to equal the "
            "totient via its prime divisors."
        ],
    )


def _run_is_prime(args: dict[str, Any]) -> HandlerEvidence:
    n = _exact_int(args.get("n"), "n")
    if n > PRIMALITY_LIMIT:
        return _guard_inconclusive(
            f"The integer {n} exceeds the primality guard bound {PRIMALITY_LIMIT}.",
            {"operation": "is_prime", "n": n},
        )
    sympy_verdict = bool(sp.isprime(n))
    recheck_verdict, witness = _miller_rabin_is_prime(n)
    if sympy_verdict != recheck_verdict:
        raise RuntimeError("primality verdicts disagreed between checkers")
    statement = f"{n} is prime" if sympy_verdict else f"{n} is not prime"
    certificate: dict[str, Any] = {
        "operation": "is_prime",
        "n": n,
        "is_prime": sympy_verdict,
        "statement": statement,
    }
    if witness is not None:
        certificate["compositeness_witness_base"] = witness
    claimed = args.get("claimed")
    if claimed is not None:
        if not isinstance(claimed, bool):
            raise ValueError("claimed must be a boolean for is_prime")
        if claimed != sympy_verdict:
            return _claim_refuted(statement, {**certificate, "claimed": claimed})
        certificate["claimed"] = claimed
    return _certified(
        statement,
        certificate,
        extra_notes=[
            "The verdict was independently rechecked with a pure-Python "
            "deterministic Miller-Rabin base set valid far beyond the guard "
            "bound."
        ],
    )


def _run_factorization(args: dict[str, Any]) -> HandlerEvidence:
    n = _exact_int(args.get("n"), "n")
    if n < 2:
        raise ValueError("factorization requires n >= 2")
    if n >= FACTORIZATION_LIMIT:
        return _guard_inconclusive(
            f"The integer {n} is not below the factorization guard bound "
            f"{FACTORIZATION_LIMIT}.",
            {"operation": "factorization", "n": n},
        )
    factors = {int(prime): int(exponent) for prime, exponent in sp.factorint(n).items()}
    product = 1
    for prime, exponent in factors.items():
        prime_ok, _ = _miller_rabin_is_prime(prime)
        if exponent < 1 or not prime_ok:
            raise RuntimeError("the factorization failed its independent recheck")
        product *= prime**exponent
    if product != n:
        raise RuntimeError("the factorization failed its independent recheck")
    statement = f"{n} = " + " * ".join(
        f"{prime}^{exponent}" if exponent > 1 else f"{prime}"
        for prime, exponent in sorted(factors.items())
    )
    certificate = {
        "operation": "factorization",
        "n": n,
        "factors": {
            str(prime): exponent for prime, exponent in sorted(factors.items())
        },
        "statement": statement,
    }
    claimed = args.get("claimed")
    if claimed is not None:
        if not isinstance(claimed, dict):
            raise ValueError("claimed must map primes to exponents for factorization")
        claimed_factors = {
            _exact_int(prime, "claimed prime"): _exact_int(exponent, "claimed exponent")
            for prime, exponent in claimed.items()
        }
        if claimed_factors != factors:
            return _claim_refuted(
                statement,
                {
                    **certificate,
                    "claimed_factors": {
                        str(prime): exponent
                        for prime, exponent in sorted(claimed_factors.items())
                    },
                },
            )
        certificate["claimed_matches"] = True
    return _certified(
        statement,
        certificate,
        cases_checked=len(factors),
        extra_notes=[
            "The product of prime powers and each prime's primality were "
            "independently rechecked with pure-integer arithmetic."
        ],
    )


def run_number_theory(spec: ExperimentSpec) -> HandlerEvidence:
    args = spec.arguments
    operation = str(args.get("operation", ""))
    if operation not in OPERATIONS:
        raise ValueError("operation must be one of: " + ", ".join(sorted(OPERATIONS)))
    if operation == "multiplicative_order":
        return _run_multiplicative_order(args)
    if operation == "crt":
        return _run_crt(args)
    if operation == "p_adic_valuation":
        return _run_p_adic_valuation(args)
    if operation == "primitive_root":
        return _run_primitive_root(args)
    if operation == "is_prime":
        return _run_is_prime(args)
    return _run_factorization(args)
