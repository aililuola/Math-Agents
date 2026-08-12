# Reasoning-First Computation Policy

MathProofMesh 0.7.0 将计算定义为“对一个精确数学判断提供可审计证据的受控子流程”，而不是第二个路线规划器。本文是 `schemas.py`、`computation/policy.py`、`computation/broker.py` 和编排器行为的规范。

## 1. 不变量

1. Explorer 必须先寻找抽象结构、代数机制、不变量、单调性、极值结构或几何关系。
2. 低成本精确反驳可以提前执行。推理优先不等于必须在错误猜想上推理到停滞。
3. 广泛枚举、模式发现和无明确命题的搜索不得走快速通道。
4. 实验结果不能直接创建 `ClaimCard`、`ProofStep` 或 `ProofCheckpoint`。
5. 同一个 Explorer 必须从同一父检查点解释结果并重新提交数学增量。
6. 未发现反例只能输出 `not_refuted`。
7. 工具异常、超时或依赖缺失只能输出无结论状态。
8. 反例必须独立复现；正面证书必须检查它与原命题之间的数学映射。
9. Planner 只能写 `ComputationHint`，不能执行提示。
10. 强类型工具必须先于模型生成 Python。
11. 已被路线采用的关键有限计算必须先取得服务器生成的类型化证书，才能进入路线、检查点或最终证明。

## 2. 状态机

```text
Planner -> StrategyCard + ComputationHint (non-executable)
  |
Explorer -> abstract reasoning
  |-- submit_attempt / submit_delta / complete / abandon
  `-- request_computation -> ExperimentSpec
          |
          v
     ComputationGate
       |-- reject -> same Explorer receives reason
       |-- defer  -> same Explorer receives reason
       `-- allow
             |-- registered typed handler
             `-- sandboxed Python only when explicitly enabled and typed_tool_gap is valid
                    |
                    v
              ExperimentResult
                    |
                    v
       same Explorer, same parent checkpoint, same segment_index
                    |
                    v
              independent Reviewer
                    |
                    v
       commit checkpoint only for an accepted mathematical delta
```

每个分段默认只允许一次计算往返。请求、门控和执行本身不会增加 `segment_index`。

## 3. Schema

### ComputationHint

Planner 可记录用途、目标、建议方法和决策用途。编排器没有执行 Hint 的代码路径，且配置禁止 `execute_planner_hints_immediately: true`。

### ExperimentSpec

核心字段：

```yaml
purpose: falsify_claim
target_claim: "精确、可判定的数学命题"
assumptions: []
reasoning_basis: "产生该判断的简短、可审计数学依据"
why_computation_is_needed: "为何不适合继续手算"
decision_if_confirmed: "没有发现问题后怎样继续，但不把结果当证明"
decision_if_refuted: "找到反例后修正或放弃什么"
noncomputational_alternative: "已考虑的非计算路线"
method: modular_exhaustive
domains: {}
arguments: {}
exact_arithmetic: true
broad_search: false
typed_tool_gap: null
max_cases: 100000
seed: 20260719
```

`reasoning_basis` 是可显示的数学理由，不是私有思维链。`arguments` 由具体强类型 Handler 解释。

系统在门控前绑定 `runtime_fingerprint`。最终 `request_hash` 覆盖：

- 规范化 ExperimentSpec；
- 工具名称和版本；
- 精度声明；
- 随机种子；
- Python 沙箱镜像的完整 digest（若适用）。

### ExperimentProgram

只有 `sandboxed_python` 使用：

- `source`：只定义 `run(data)` 的源码；
- `input_schema` / `output_schema`：JSON Schema；
- `dependencies`：声明的依赖；
- `code_hash`：源码 SHA-256。

### ExperimentResult

结果包含：目标命题、方法、范围、反例或证书、精度、检查案例数、运行时间、工具版本、独立复核标志、artifact 引用和结果哈希。

`result_hash` 不包含实验 ID、路径归属、运行时长和缓存命中标志，从而允许同一语义请求跨恢复点复用；它包含请求哈希、目标命题、结果内容、工具版本和独立复核状态。

## 4. Gate 规则

### Reject

- 计算功能关闭；
- 目标命题过短或含“枚举看看”“看看规律”等模糊意图；
- 缺少足够的数学依据或明确的确认/反驳决策用途；
- 超过单实验案例上限；
- 路径达到硬实验配额；
- 工具未注册或强类型工具关闭；
- 请求 Python 沙箱但沙箱关闭；
- Python 请求没有说明 `typed_tool_gap`；
- Planner Hint 试图自动执行。

### Defer

- 广泛搜索尚未达到停滞轮数；
- 广泛搜索没有 Meta-Reviewer 批准；
- 路径超过软实验配额且没有 Meta 批准；
- 总 CPU 预算耗尽；
- 执行后没有足够模型调用预算让 Explorer 解释结果。

### Allow

- 相同缓存键已有结果；或
- 精确定义、受预算约束的定向反驳；或
- 具有明确数学和决策用途的其他必要定向检查；或
- 已停滞且经 Meta-Reviewer 批准的广泛搜索。

快速通道只绕过“必须先停滞”，不绕过精确目标、预算、工具安全、缓存和独立复核。

### Route-critical finite calculation gate

该门控与 Explorer 主动申请的 `ExperimentSpec` 不同。它处理的是已经出现在 `StrategyCard` 或 `ProofStep` 中、且将被当作前提使用的显式有限计算：

1. Planner、Explorer 或 Synthesizer 在原有结构化响应里填写 `calculation_checks`，不会为正确请求增加一次模型调用。
2. 系统只接受能核验所声明答案的断言式类型化工具。只生成一个结果、却不比较声明值的请求不能作为准入证书。
3. 门控在路线首次调用前、增量检查点审查前和最终 Reviewer 调用前执行。
4. `missing_declaration`、`invalid_contract`、`inconclusive` 和 `refuted` 都会失败关闭；原因和实验哈希进入结构化审计记录。
5. `calculation_evidence_refs` 完全由服务器根据实际实验工件写入，模型返回的引用会被清空。
6. 已确认的反例阻止受影响前提继续使用；精确的有界非反驳只支持声明的有限范围，不升级为无限结论。

默认高精度触发范围是显式数列值、声称的最小值、有限枚举和周期样本。普通的逐行代数展示不会仅因写入 `ProofStep.calculations` 就触发工具，以免把证明本身变成不必要的计算流水线。

## 5. Handler 契约

### symbolic

表达式先通过 AST 白名单，再交给 SymPy。私有名称、属性访问、任意函数调用和字符串常量被拒绝。符号等价只有在差精确化简为零时才产生形式证书。

### modular

遍历全部声明剩余类。若发现违反赋值，精确重新代入。只有每个变量确实覆盖模数的全部剩余类、请求给出 `finite_reduction: true` 和明确归约理由时，才产生 `exhaustive_certificate`；Reviewer 仍需验证归约理由。部分剩余类通过只能得到 `bounded_evidence`。

### bounded_integer_search

通过受限算术 AST 构造 Z3 整数约束。Z3 模型由另一套纯整数求值器重新代入。`unsat` 只证明声明的有限域无反例。

### graph_certificate

支持着色、路径/圈、匹配和连通性证书。证书必须同时通过 NetworkX 和独立的属性专用检查器。

### recurrence_check

使用 `Fraction` 生成声明区间内的线性递推，并精确比较候选公式。区间通过只得到 `bounded_evidence`。

### exact_geometry

使用有理坐标、行列式、包围盒和平方距离，不从浮点近似推出几何结论。

### numeric_counterexample

使用固定种子抽取有理数。候选违反点再以更高精度/精确表达式回代。未找到反例保持 `heuristic`。

### Broker replay

Handler 标记的每个反例会立即由 Broker 再执行一次确定性工具；重放无法产生独立有效反例时降级为 `inconclusive`。Checkpoint、候选证明和最终 Reviewer 收到系统独立重放记录；终审再次重放所有 `counterexample`、`exhaustive_certificate` 和 `formal_certificate` 结果，并校验其他实验的内容哈希和工具身份。

## 6. 证据到验证结论的映射

| ExperimentResult | 可否独立提升 PASS | 确定性行为 |
| --- | ---: | --- |
| `heuristic/not_refuted` | 否 | 仅供 Explorer 调整方向 |
| `bounded_evidence/not_refuted` | 否 | 只记录声明范围 |
| 已复核 `counterexample` | 可否定 | 若被反驳命题仍用于证明，强制 FAIL |
| `exhaustive_certificate` | 否 | Reviewer 先验证有限归约 |
| `formal_certificate` | 否 | Reviewer 先验证命题映射 |
| `inconclusive` | 否 | 工具异常、超时或依赖缺失不判数学失败 |

Reviewer 可以在报告中发出新的 `ToolRequest`。Checkpoint Reviewer、候选证明 Reviewer 和 Final Reviewer 都会执行这些请求；工具失败会阻止它作为通过依据，但不会自动判定命题为假。关键实验的独立重放失败会以执行级问题阻止检查点或终审 PASS，而不是宣称原数学命题为假。

Explorer 收到已确认反例后，必须把影响明确分类为 `execution`、`plan` 或 `strategy`，并修正或放弃受影响路线；缺少分类时系统不会提交新的证明检查点。

## 7. Python 沙箱

沙箱默认关闭。启用条件：

1. `sandboxed_python_enabled: true`；
2. 镜像使用 `name@sha256:<64 hex>` 固定；
3. `ExperimentSpec.typed_tool_gap` 说明注册工具为何无法表达请求；
4. Gate 预留一次代码生成调用和一次 Explorer 解释调用。

Docker 命令包含：

- `--network none`；
- `--read-only`；
- `--cap-drop ALL`；
- `no-new-privileges`；
- 非 root UID；
- CPU、内存、PID、超时和输出上限；
- 仅将临时实验目录只读挂载到 `/work`；
- 不挂载项目工作区；
- 启动 Docker 进程时使用空宿主环境。

源码 AST 拒绝文件、进程、网络、动态执行、动态导入、私有属性和直接输出 API。容器通过 stdin 接收 JSON，由固定 bootstrap 调用 `run(data)` 并输出受长度约束的 JSON。输入 Schema 必须要求运行器注入的整数 `seed`，输出 Schema 必须要求 `outcome`、`cases_checked`、`scope` 和 `exact_arithmetic`。

沙箱正面结果被强制降为 `bounded_evidence`。沙箱反例在强类型工具复现前保持 `inconclusive`。

## 8. 规律发现的语义闭环

成功的 `discover_pattern` 实验不能只停留在有限样本。Explorer 必须同时提交结构化 `CandidateConjecture`，明确写出可证伪的公式、递推式或不变量，并绑定准确的 `experiment_id`、有限证据的适用限制以及后续符号证明义务。

如果主响应漏掉候选规律，编排器最多调用一次禁用 Thinking、输出上限 4096 tokens 的小型补全阶段；它只解释已有结果，不重做证明或再次计算。补全仍失败时，路线强制保持 `partial`，并保留“形成明确候选规律”的开放义务。

证据引用由服务器绑定到 `execution.json`、`result.json` 和原始候选响应。候选规律会进入路线记录和运行报告，但不会进入 ClaimMemory、Broker Fact 或已验证检查点；只有另行完成证明并通过独立审核后，相关命题才可能升级为事实。

## 9. 持久化和恢复

```text
runs/<run_id>/experiments/<request_hash>/
|-- spec.json
|-- decision.json
|-- program.json
|-- program.py
|-- execution.json
|-- result.json
`-- evidence.json
```

`experiments/ledger.json` 记录每条路径使用过的唯一请求、实际执行请求、路径配额和总 CPU 时间。Broker 初始化时加载 Ledger 和缓存。同一路径重复请求相同缓存键不会重复计数；跨路径复用会记录证据归属，但不会再次执行或增加 CPU 时间。

`execution.json` 完整记录规范化执行输入、原始 Handler 输出、系统接受的结构化结果、程序哈希、工具环境以及各自 SHA-256。最终审计会校验 `spec`、`program.py`、`execution`、`result` 和 `evidence` 之间的绑定关系，并重放关键证据。

## 10. Activity

持久化事件包括：

- `computation_decision`；
- `experiment_completed`；
- `experiment_cache_hit`；
- `experiment_impact_classified`；
- `pattern_conjecture_completed` / `pattern_conjecture_completion_failed`；
- `final_experiment_audit`。

Activity 只显示目标哈希、门控规则、证据等级、案例数和运行时间，不显示模型私有思维链或凭据。

## 11. 测试门槛

自动化回归覆盖门控快慢路径、证据非对称性、Checkpoint 不推进、工具异常、独立反例覆盖、缓存恢复、各强类型 Handler、Docker 安全参数和离线枚举密集代理基准。

真实模型的 token/费用/正确率对比需要用户提供 API key，因此不是默认 CI 的一部分。离线基准只验证：把逐例文本替换为结构化请求能显著降低可见推理文本，同时强类型工具对已知基准保持正确。

## 12. 统一身份、契约模式与任务状态

- `request_hash` 是持久化证据的规范标识；运行内的 `request_id`、
  `experiment_id` 和缓存复用产生的别名都写入
  `experiments/computation_identity_index.json`。验证器先解析该索引，
  标识存在冲突时拒绝猜测。
- `execution_hash` 只覆盖实际工具、规范化参数、定义域、精度和运行时工具
  指纹。目标描述或决策说明不同但执行输入相同的确定性请求复用已经成功的
  结果；错误或 `inconclusive` 结果不参与复用。
- 发现模式与断言模式分离。`discover_pattern` 可生成有限样本但只能支持
  `CandidateConjecture`；断言模式必须携带可核验的声明值。未携带
  `claimed_values` 的非关键数列请求会降级为发现模式，不能取得定理资格。
- 关键计算触发器只识别带明确计算来源的数值结果或明确生成的数列前缀。
  符号极值证明、正常代数推导和符号化有限分类不会仅因出现“最小值”等词
  被拦截。
- `task_status` 与 `math_status` 分开。完成用户要求的计算或提出带范围限制的
  猜想可以得到 `task_status=completed`，同时猜想仍保持
  `math_status=inconclusive`；明确要求证明或求全部解时仍必须通过最终独立
  审计。
