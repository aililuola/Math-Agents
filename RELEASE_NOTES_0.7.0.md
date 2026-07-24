# MathProofMesh 0.7.0

- 新增“关键有限计算证书门控”。当策略或证明步骤把显式数列项、最小值、有限枚举或周期样本作为承重前提时，必须在同一次结构化响应中声明 `calculation_checks`；系统会在路线启动、检查点审查和最终审计之前用注册的确定性工具核验。缺少声明、契约无效、结果无结论或发现反例都会阻止该前提进入证明状态；通过后由服务器写入不可伪造的证据引用。有效请求不增加模型调用，有限证书也只覆盖其声明的有限范围。
- 定向计算现在统一经过覆盖全部 13 种计算方法的预执行契约层，未知字段、缺失参数、错误容器和明显不合法的有界范围不会再进入处理器。一次窄化、禁用 Thinking 的修复调用只能提交修正后的 `ExperimentSpec` 或放弃，不能夹带 Proof Delta；批量、聚合及自定义有限计算若无法由单一 typed tool 表达，只能在语义和边界保持不变后转入隔离 Python 沙箱。原始/修复请求哈希、修复 Agent、决策与失败原因均进入审计工件和桌面计算面板。
- Windows 恢复运行保存大型 `typed_memory.json` 快照时，原子替换现在会在短暂共享锁后按上限退避重试，同一运行内的工件写入也会串行化。瞬时的 Defender、索引器或读取占用不再直接终止恢复；若权限持续异常，旧的完整快照仍保持不变。
- `bounded_greedy_sequence` 现在向 Explorer 明确发布参数契约，并在执行前拒绝未知别名、缺失初值/长度/规则以及不受支持的多初值域扫描。新增 `gcd_overlap_all_prior` 精确实现“每个新项与所有先前项的最大公因数均大于 1”；有限前缀仍只算有界证据，不会升级为证明。
- 结构类比调度现在先检索与当前题目和义务实际匹配的已验证本地记录。全局类比库虽非空但本题检索为空时，任务会无成本延后，不再让模型在“不得伪造来源”和“必须输出完整类比映射”的冲突 Schema 中反复失败。
- 新增所有解题调用之前的“题意预检与目标规范化”。高精度本地规则未发现疑点时原题原样冻结且零 API 成本；发现缺失模数、参数、占位内容或外部上下文时，仅调用一次禁用 Thinking、4K 上限的 Planner 结构化检查。任何补充或改变数学含义的候选必须由用户确认，原题与规范化目标分别保存，并以同一个 `goal_hash` 约束后续流程。
- 桌面端会在题意确认期间暂停运行并显示原题、歧义原因、推荐解释、备选解释和置信度。确认前不会启动 Planner、Explorer、Synthesizer 或 Verifier；确认记录、Agent 来源和目标哈希进入运行工件、时间线与拓扑图。
- Reverse Goal 的双向前沿不再由词法重叠直接生成 `A implies B`。前向 Fact 只能作为候选工具，必须重新证明变量映射、作用域适用性和缺失前提；低相关 Fact 不再参与桥接。
- DeepSeek 冒烟、正式及 Active 配置将 `segments_per_explore_call` 调整为 2。每个 Delta 仍独立审核，首段通过后可在同一编排动作中安全推进第二个子目标。
- 为覆盖第二个独立验证段及后续收尾储备，冒烟版调整为 40 次调用和 3 轮，标准正式版调整为 60 次调用；两者的费用与总 Token 硬上限保持不变。

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
- Active Inspiration proposer assignment now derives its population from all
  enabled agents with the configured specialist or generalist roles. It never
  assumes a fixed API-key count. Multiple available agents are used at most
  once per task; a singleton pool is limited to one warm and one cold sample.
  Assignment happens before admission, so reservations use the actual
  proposer count and unused candidate capacity remains available to other
  mechanisms. Assignment plans are checkpointed and emitted to Activity.

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
- Active output keeps a 384K provider ceiling and uses evidence-gated 64K, 96K
  and 128K operating tiers. Distinct mathematical signatures may run
  high tiers concurrently; repeated high-cost work on the same checkpoint,
  target and mechanism is suppressed.
- Ordinary initial routes now start at 96K with max-effort Thinking. The 64K
  tier remains only for one bounded same-signature or ambiguous-novelty repair;
  it is no longer the default exploration tier. The smoke Route Provers and
  continuation segment cap were raised accordingly to 96K.
- DeepSeek Thinking is selected per stage: routine artifact stages use disabled
  or high; admitted 96K/128K route work uses max. Strategy generation is an
  explicit planning exception at 128K/max in smoke and 384K/max in both formal
  presets. Empty length
  responses do not trigger another full Explorer call; one non-thinking
  8K/12K/16K public-checkpoint diagnostic is used instead. Its call and token
  capacity are reserved atomically before the corresponding deep call.
- Attempt-level incompleteness no longer blanket-rejects independently audited
  local lemmas. Claim status reconciliation is scoped by explicit claim/step
  findings, and Fact promotion uses the Route Team review for the Claim's exact
  source delta. The smoke profile now has a 500,000-token run budget.
- Scheduler evidence now follows each route's accepted and rejected ProofDelta
  history instead of only the newest Attempt ID. A blank recovery Attempt cannot
  erase a rejected checkpoint, while an incomplete-only final submission does
  not invalidate independently checked partial progress. Authoritative
  Meta-Review STOP/COOLDOWN controls are enforced by RouteRegistry, and the
  selected DEEPEN/REVISE route receives an explicit ranking preference. The
  smoke and formal profiles share this implementation.

## Compatibility

`legacy_sparse` remains the default for generic configuration and preserves the
0.6 workflow. The DeepSeek formal profile keeps hierarchical graph and
inspiration behavior in shadow mode. The smoke profile keeps the proof graph in
shadow mode but enables active inspiration materialization. The separate
`config.deepseek-v4-pro.topology-active.yaml` enables both active graph control
and active inspiration at the high-budget settings.

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
- The complete offline suite passes 266 tests with `.[dev,server]` installed,
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

## Advanced Inspiration Completion

- Added a typed domain-operator registry for number theory, combinatorics,
  inequalities and geometry. Every operator carries preconditions, generated
  obligations, reversibility requirements, fast falsification tests, known
  failure modes and suggested tools.
- Surprise exploration now uses deterministic, replayable structural mutation
  directives. Active model output cannot replace the scheduler-admitted seed or
  operator with an unrelated mutation.
- Reverse-goal analysis now maintains Broker-Fact-only forward frontiers and
  sufficient-condition backward frontiers, materializing only explicit missing
  implications where those frontiers nearly meet.
- Added `InspirationComposer` for complementary, independently reviewed ideas.
  A composition requires a passed quick falsification, is queued for a later
  scheduler turn, and must pass its own Referee and Skeptic before route
  materialization. It never enters FactMemory directly.
- Added optional project-local cross-run learning. Only final-cited verified
  positive experiences, failed analogy records and public outcome metrics are
  persisted under the git-ignored `.mathproofmesh/learning` directory. Prompts,
  private reasoning, raw provider output and secrets are excluded.
- Checkpoint, Activity, hierarchical metrics, Mock benchmark and regression
  coverage now include operators, mutations, frontiers, compositions and
  cross-run learning. The benchmark continues to make zero provider calls.

## Explicit Inspiration Credit Attribution

- Added checkpointed `InspirationCreditTarget` records linking every proposal
  to its materialized route, obligation and Broker message IDs. Verified Fact
  promotion, obligation closure and final-proof citation now use these IDs
  instead of depending only on a newly created Strategy.
- Existing-route attachments, computation and bridge requests,
  obligation-only materializations and all sources of a composed proposal can
  receive downstream credit without bypassing the Fact gate.
- Outcome registration freezes the route and obligation measurement sets, so a
  Surprise proposal with no initial target route cannot report a false proof
  debt reduction by comparing a nonempty before-set with an empty after-set.
- UCB and minimum exploration now consider only enabled members of
  `SCHEDULABLE_MECHANISMS`. The derived `INSPIRATION_COMPOSITION` mechanism no
  longer distorts the ordinary exploration floor.

## Certified Progress And Hard Stagnation Control

- Proof-state signatures now exclude attempt, checkpoint, obligation, route and
  Agent IDs. Nested feedback wrappers are removed before hashing, while
  assumptions and quantifier scope remain part of obligation identity.
- Repeated attempts and same-route obligations are collapsed by public
  mathematical content. Raw responses remain immutable artifacts, and
  cross-route copies stay distinct so bridge detection retains its topology.
- Every adaptive round writes a progress certificate based only on verified
  checkpoint mathematics, Broker-admitted Facts, evidence-backed obligation
  closure/refutation and independently checked counterexamples.
- An unchanged route receives one normal attempt and one bounded repair.
  Another unchanged result freezes that signature. Two globally stagnant
  rounds request one meta-strategy pivot; three stop repeated solving while
  preserving all verified checkpoints and resume state.
- Strategy cards now identify load-bearing critical claims and their fast
  falsification tests. Independently checked counterexamples update those
  claims and deterministically refute or require repair of the affected route.
- Invalid typed-computation contracts do not consume experiment quota. They
  receive at most one 8K, Thinking-disabled structured repair; a second invalid
  contract freezes the route instead of restarting deep reasoning.
- Goal-integrity failure is a hard schema gate: a verifier cannot return
  `problem_integrity_ok=false` together with a passing verdict.
- HTTP 402 opens the provider circuit immediately. Repeated 401/403 or terminal
  5xx failures across distinct keys open the shared circuit, while 429 remains
  a bounded retry/backoff condition. Circuit state survives checkpoint resume.

## Directed Computation And Stream-Stall Recovery

- Exact registered typed probes with at most 25,000 declared cases may run
  before route stagnation, including bounded pattern discovery. Their output
  remains bounded evidence and cannot certify an infinite statement.
- Deferred experiment requests are stored in a durable per-run queue and are
  re-evaluated before the next route call using actual route stagnation and
  Meta-Reviewer state. A defer no longer triggers another full reasoning call.
- Route computation admission now uses certified route stagnation and
  no-progress strikes rather than the outer round number.
- Streaming calls have a separate 90-second first-chunk timeout and the
  existing five-minute mid-stream idle timeout. Distinct-key transport stalls
  feed the shared provider circuit breaker.
- Calls waiting for an Agent/API concurrency slot are labeled `queued` instead
  of reporting fictitious `0 chunks` live progress.
- Optional claim extraction distributes work across distinct Agents and
  cancels the remaining batch when the shared provider circuit opens, retaining
  Explorer-proposed lemmas instead of blocking the solve.
- The offline directed-computation smoke problem creates one live computation
  node, executes `bounded_greedy_sequence`, and verifies the exact prefix
  `6, 8, ..., 28`.

## Auditable Pattern Completion And Usage Accounting

- A successful `discover_pattern` experiment must now produce a structured
  `CandidateConjecture` with the concrete formula, exact supporting experiment
  IDs, finite-scope limitations and separate proof obligations.
- If the route response omits that interpretation, the orchestrator makes at
  most one small Thinking-disabled completion call. Failure leaves the route
  partial instead of silently discarding the useful computation or inventing a
  theorem.
- Candidate evidence references are attached by the server to the audited
  execution, result and raw proposal artifacts. Candidates remain route-local,
  appear explicitly as unproved hypotheses in reports, and cannot enter global
  FactMemory without an independent proof.
- Computation plans preserve both the confirmed and refuted decisions together
  with the exact argument, domain and case bounds used by the tool.
- Usage totals are reconciled from input and output tokens after every validated
  mutation, preventing provider totals from being counted a second time.
- Packaged window smoke tests use an isolated single-instance mutex, so a
  currently running installed copy no longer blocks release validation or
  opens a modal error during the build.
- The offline directed-computation smoke now verifies the full semantic path:
  exact values `6, 8, ..., 28`, candidate
  `a_n = 2n + 4` (equivalently `a_{n+1} = a_n + 2`), evidence linkage,
  non-promotion to fact status and consistent token accounting.

## Computation Identity, Contracts, And Task Completion

- A durable computation identity index now binds `ToolRequest.request_id`,
  model-authored `experiment_id`, canonical `request_hash`, and every cache
  alias. Evidence consumers resolve aliases without guessing, while ambiguous
  identifiers fail closed.
- `execution_hash` covers only the normalized executable invocation. Equivalent
  deterministic requests with different prose or harmless case-budget changes
  reuse one successful result and one topology node; inconclusive or failed
  executions are never reused as answers.
- The critical calculation trigger now requires explicit computational
  provenance or a concrete generated sequence prefix. Words such as
  "minimum" in a symbolic extremum proof no longer trigger a computation gate.
- `bounded_greedy_sequence` has separate discovery and assertion semantics.
  Discovery may omit `claimed_values` and can create only a scoped
  `CandidateConjecture`; assertion mode requires values to check. A noncritical
  unclaimed request is safely downgraded to discovery instead of being treated
  as theorem evidence.
- Run results now report `task_status` and per-deliverable assessments
  separately from `math_status`. A requested computation-and-conjecture task
  can finish as `completed` while its conjecture correctly remains unproved;
  explicit solution and proof requests still require independent final audit.
