# MathProofMesh 0.7.0

## Highlights

- Added an opt-in hierarchical sparse topology with route-local prover,
  skeptic, tool and referee teams.
- Added typed mathematical messages, sparse broker routing, receipts,
  exactly-once resume and strict Fact/Insight/Negative evidence boundaries.
- Added the Proof Obligation Graph, proof debt, bridge and contradiction
  brokers, mechanism-aware duplicate-route handling and blind final audit.
- Added the complete P0 Inspiration Engine: Representation Switchboard,
  verified-local Analogy Agent, Auxiliary Construction Inventor,
  Invariant/Monovariant Agent, Reverse Goal Analyzer, Persistent
  Meta-Strategist, Surprise Budget, Novelty Gate and independent Inspiration
  Referee.
- Added validation escalation, proof-mutation evaluation, domain-role
  capability profiles and a selective formal micro-certificate interface.
- Added stable topology/inspiration checkpoint state, Activity events, reports,
  six offline benchmark cases and eleven required ablations.

## Active Runtime Closure

The active profile now exercises the hierarchical components in the live proof
path rather than only recording post-hoc topology metadata:

- Typed prompt context is recursively JSON-normalized, including nested
  Pydantic models, enums, dataclasses, paths and collections.
- Each route reads its bounded Broker inbox and typed memory before the next
  continuation call, then returns a semantic receipt for every delivered
  message. The Broker recomputes receipt hashes and persists exactly-once
  delivery state across resume.
- Ordinary active routes run through Prover, risk-directed Skeptic/Tool and an
  independent Referee before an artifact may be promoted to global FactMemory.
  Missing or failed required roles fail closed and leave the artifact local.
- Active Inspiration, Bridge and Contradiction typed calls are covered by
  end-to-end deterministic tests. Inspiration and route-team state are emitted
  as stable structured artifacts for audit.

## Secondary Audit Closure

- Hierarchical Route Provers receive no legacy global ClaimMemory context;
  all cross-route premises must arrive through Broker delivery and TypedMemory.
- Receipts independently restate ordered quantifiers and variable bindings.
  A scope or quantifier reversal is rejected before checkpoint advancement.
- Prover, Skeptic, Tool Specialist and Referee assignments are pairwise
  distinct. Missing independence leaves the artifact route-local.
- Active hierarchical configuration now requires continuation, so users cannot
  accidentally bypass the live typed route pipeline.
- Validation escalation has an executor for deterministic, blind,
  adversarial, cross-provider and tool/formal evidence. Agent capability
  scores now participate in domain-role dispatch.
- Global Fact selection is purpose-aware. Explicit `message_id` or
  `content_hash` dependencies and their admitted dependency closure precede
  lexical similarity; missing required Facts fail closed.
- Blind final packets bound NegativeMemory by item and character budgets while
  preserving exact counterexamples and explicit conflicts first. Omitted
  mandatory negative evidence prevents final PASS.
- Blind artifact evidence contains only the file-content SHA-256, certificate
  type and replay status. Raw run paths are never exposed to Blind Judges.
- Inspiration tasks pass unified scheduler admission before model calls;
  `require_inspiration_referee` and `max_new_routes_per_trigger` are enforced.
- Message utility is credited only after a committed, verified delta cites the
  message or closes a claimed obligation. Receipt acceptance alone is zero.
- Computation-backed route artifacts invoke a real independent Tool Specialist
  typed prompt in addition to deterministic replay.
- Active output keeps a 384K provider ceiling and uses evidence-gated 32K,
  64K, 96K and 128K operating tiers. Distinct mathematical signatures may run
  high tiers concurrently; repeated high-cost work on the same checkpoint,
  target and mechanism is suppressed.

## Compatibility

`legacy_sparse` remains the default for generic configuration and preserves the
0.6 workflow. The DeepSeek formal and smoke profiles start hierarchical graph
and inspiration behavior in shadow mode. The separate
`config.deepseek-v4-pro.topology-active.yaml` enables active materialization.

No API key, `.env`, run output, provider response, cache, wheel or source archive
is part of this release commit.

## Validation

The release gate runs the complete Pytest suite, Ruff check and format check,
`compileall`, all shipped configuration parses and the offline topology
benchmark. Real provider calls are intentionally excluded from automated
validation.

## Final P0 Audit Closure

- Rejected `InspirationProposal` values now use an exact blind-negative
  serializer. The packet preserves the mathematical proposal and rejection
  rationale without exposing route or Agent identity.
- Hierarchical verification, synthesis, blind review and final revision now
  share one admissible global-fact policy. A global premise must be a live
  TypedMemory Fact admitted by the Broker with independent-referee provenance;
  unresolved dependencies fail closed.
- Legacy LemmaMemory remains available to `legacy_sparse`, while hierarchical
  resume quarantines legacy-only checkpoint claims instead of promoting them.
- Dynamic and AST regressions cover rejected active Inspiration, rejected
  route-local claims, verifier tool follow-ups, synthesis, blind packets,
  revision, dependency gates and old-checkpoint resume.

## Legacy Checkpoint Resume Closure

- A checkpoint saved after triage but before Strategy generation now rebuilds
  every missing Route, Prover membership and sparse neighborhood before any
  resumed proof call.
- The actually selected Prover is synchronized into RouteRegistry. Route
  repair is idempotent and persisted before the resumed exploration round.
- `hierarchical_sparse` fails closed when Route, Broker or TypedMemory context
  is missing and can no longer fall back to the legacy `proof_continuation`
  prompt or LemmaMemory inbox.
- Hierarchical reports distinguish Broker-admitted global Facts from legacy
  ClaimMemory history. `reports/global_fact_inventory.json` records both sets
  and the qualification policy.
- The complete offline suite passes 226 tests with `.[dev,server]` installed,
  including `z3-solver`. The topology benchmark is a deterministic component
  contract Mock with zero provider calls, not a real IMO performance claim.

## Diverse Inspiration Candidate Population

- An admitted active Inspiration task now generates three independent typed
  candidates concurrently instead of treating one model sample as the whole
  mechanism search. The default population contains two bounded warm-context
  candidates and one de-anchored cold-context candidate.
- Warm context is mechanism-specific and includes only a small relevant set of
  Broker-admitted Facts, bounded NegativeMemory and the minimal target graph.
  Cold context excludes route proof prose, Facts and negatives while retaining
  the problem, target obligation and a forbidden-mechanism list.
- A canonical mechanism ontology separates representations, principles,
  transformations and mathematical objects. Raw and unknown extension labels
  remain auditable, but unknown labels cannot independently trigger duplicate
  rejection.
- The Novelty Gate deduplicates the candidate population before expensive
  review. At most two candidates per task reach independent Referee/Skeptic
  calls, and at most one proposal per trigger creates a new route.
- Scheduler admission atomically reserves proposer, Referee, Skeptic and first
  route-attempt calls. Checkpoint resume reconciles charged calls and releases
  unused or interrupted reservations. Activity and hierarchical metrics expose
  every selection and budget transition.
- No global Inspiration or high-tier semaphore was added. Distinct domains,
  subdirections, local obligations and mechanism pivots may continue in
  parallel subject to existing Agent concurrency and total run budgets.

## Persistent Strategy Control And Learning

- Persistent Meta-Strategist output now becomes an audited `MetaDirective`,
  never a normal Insight. Accepted directives deterministically merge, cool or
  abandon eligible routes, or enqueue a typed mechanism task for ordinary
  scheduler admission. Shadow mode remains mutation-free.
- A checkpointed `InspirationOutcome` ledger attributes proposal, review and
  route calls; token cost; verified Fact gain; proof-debt change; closed
  obligations; refutation; time to first gain; and final-proof use.
- Mechanism ordering uses deterministic UCB scores conditioned on domain,
  trigger and obligation kind, with a configured minimum exploration floor.
  Learned scores cannot enter FactMemory or influence mathematical verdicts.
- A Verified Experience Distiller admits positive analogy records only after an
  independently reviewed Fact passes the Broker gate. Failed transfers enter a
  separate Negative Analogy Library and suppress the same source for the same
  problem.
- Directives, audits, executions, outcomes and both experience libraries are
  included in checkpoints, Activity, run artifacts and hierarchical metrics.
