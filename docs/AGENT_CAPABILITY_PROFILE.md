# Agent Capability Profile

Trust is indexed by `(agent_id, mathematical_domain, role)`, never by one global
agent score. Supported domains are number theory, combinatorics, algebra,
inequalities, geometry, logic and computation. Roles include prover, skeptic,
route referee, structural verifier, detailed verifier, analogy agent,
construction inventor and tool agent.

Each cell records observations, decayed weighted success, total weight,
overturns and a bounded score. Updates use proof-mutation detection, exact-tool
agreement, first-error accuracy, later overturns and recent task outcomes.
`min_observations_before_trust_update` and every weight are configured.

Self-reported confidence is ignored and counted diagnostically. It cannot
change capability. A verifier that accepts a known-bad mutation loses only the
relevant domain/role score; the same agent's unrelated role is unchanged.
Likewise, agreement with an exact tool can improve the corresponding
tool/verifier cell.

Profiles are checkpointed and restored with the topology state. They may inform
assignment and escalation, but they cannot bypass evidence, independence,
scope or FactMemory gates.
