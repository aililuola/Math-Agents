# 第 1 个问题修复记录：冻结并审计原题精确目标

## 1. 文档状态

| 项目 | 结果 |
| --- | --- |
| 问题编号 | 001 |
| 修复分支 | `fix/001-exact-goal-contract` |
| 基准分支 | `java` |
| 实现提交 | `d3e0632993d00d34e9a9a20e523444138d340bce` |
| 实现提交信息 | `fix(proof-control): enforce immutable exact root goal semantics` |
| 专项测试 | 通过，14 tests，0 failures，0 errors |
| core 全回归 | 通过，928 tests，0 failures，0 errors |
| 工作范围 | 只处理原题目标在 Triage / Problem Semantic View 中发生漂移的问题 |

## 2. 原问题

原题要求证明：

```text
存在固定正整数 T,L，使得对于每一个正整数 n，
a_{n+T}=a_n+L。
```

必须保持以下三个结构：

1. 指标范围是全局的 `forall n >= 1`，不能改成 sufficiently large 或 eventually。
2. 结论是指标平移周期性，不能改成等差数列或一阶差分恒定。
3. 量词次序是 `exists(T,L) -> forall(n)`，且同一组 `T,L` 对所有 `n` 统一。

修复前，`ProblemSemanticViewCandidate` 中的 `preserves_*` 字段由模型自行填写，
`ProblemSemanticViewService` 没有独立检查上述细粒度结构。模型错误地全部返回
`true` 时，错误英文视图存在通过的可能。

此外，旧 `ProblemSemanticViewParityTest` 的多个测试名称实际都调用同一个数学片段
提取场景，没有执行名称所描述的不同语义漂移。

## 3. 修复边界

本次只修改：

- Root Goal Contract 冻结；
- 指标范围审计；
- 量词骨架和量词次序审计；
- 统一见证量作用域审计；
- 结论类型审计；
- Problem Semantic View 的确定性准入；
- 英文 sidecar 的生产附加路径；
- 单轮、绕过和 20 轮回归测试；
- 原名义 parity 测试的真实场景化。

本次没有修改：

- Negative Memory；
- Claim 生命周期；
- Proof Graph 合并；
- Pivot；
- Broker；
- 并行调度；
- Token 或预算；
- DeepSeek Provider；
- Temporal；
- PostgreSQL；
- 数据库迁移或公开 JSON Schema。

## 4. 生产代码修改

### 4.1 `ExactGoalContractChecker`

新增：

```text
mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/
ExactGoalContractChecker.java
```

确定性提取 `GoalSignature`：

- `indexScope`；
- `uniformityScope`；
- 有序 `quantifierSkeleton`；
- `conclusionShape`；
- 提取置信度。

结论类型包括：

- `INDEX_TRANSLATION_PERIODICITY`；
- `CONSTANT_FIRST_DIFFERENCE`；
- `MIXED_OR_CONTRADICTORY`；
- `UNKNOWN`。

实现特性：

- 使用 NFKC 规范化和 `Locale.ROOT`；
- 复用 `ScopeGuard.extract(...)` 判断 `ALL`、`EVENTUAL` 等范围；
- 支持中文、英文、LaTeX 和 Unicode 量词标记；
- 按出现顺序提取 `exists(...) -> forall(...)` 等量词骨架；
- 识别 `T(n)`、`L(n)` 及自然语言依赖关系；
- 公式分类不硬编码 `a,n,T,L`，变量改名后的同构公式仍可识别；
- source 已识别而 target 无法解析时 fail closed；
- source 不属于当前支持的序列目标时返回 `not_applicable`，不误伤普通题。

新增稳定审计项：

- `index_scope`；
- `quantifier_skeleton`；
- `uniform_witness_scope`；
- `conclusion_shape`。

新增稳定错误码：

- `INDEX_SCOPE_MISMATCH`；
- `QUANTIFIER_ORDER_MISMATCH`；
- `UNIFORM_WITNESS_SCOPE_MISMATCH`；
- `CONCLUSION_SHAPE_MISMATCH`；
- `MIXED_CONCLUSION_INTERPRETATION`；
- `RECOGNIZED_SOURCE_UNPARSEABLE_TARGET`。

### 4.2 `RootGoalContract`

新增：

```text
mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/
RootGoalContract.java
```

该不可变 record 保存：

- 原始 source statement；
- source statement hash；
- 冻结时提取的 `GoalSignature`。

`freeze(...)` 只从原始题目创建契约。构造器重新计算哈希并使用常量时间字节比较
验证文本与哈希一致，避免构造不匹配的根目标。

### 4.3 `ProblemSemanticViewService`

保留兼容方法：

```java
build(String sourceStatement, ProblemSemanticViewCandidate candidate)
```

新增正式方法：

```java
build(RootGoalContract rootGoal, ProblemSemanticViewCandidate candidate)
```

新的 `build(...)` 合并：

1. `SemanticProfileService.audit(...)`；
2. `ExactGoalContractChecker.audit(...)`。

只有数学片段未丢失且所有确定性审计均无 `fail`，才设置：

```text
deterministic_audit_passed = true
```

模型返回的四个 `preserves_*` 字段仍是必要条件之一，但不再构成充分条件。

新增生产附加方法 `attach(...)`：

- 校验 `ProblemContract` 与 `RootGoalContract` 文本和哈希一致；
- 只把 `usable`、确定性审计通过且 `authoritative=false` 的视图附加为 sidecar；
- 当前候选被拒绝时不附加该候选；
- 如已有合法 sidecar，则拒绝新候选时保留旧合法 sidecar；
- 被拒绝的英文文本不会成为下一轮主目标。

### 4.4 `ProblemContract`

新增 `withSemanticView(...)`，附加前验证：

```text
semantic_view.source_statement_hash == problem.goal_hash
```

没有增加字段，没有修改公开 JSON Contract，也没有数据库迁移。

### 4.5 `SemanticProfileService`

补充量词识别标记：

- `每一个`、`对所有`；
- `there exist`；
- `\\forall`、`forall symbol`；
- `\\exists`、`exists symbol`。

同时允许中文 source 中的“证明”任务被忠实的英文陈述式定理表达保留，前提是 target
没有出现相反任务动词。

### 4.6 生产调用链

`DesktopSolveCoordinator` 在原题冻结后立即创建一次 `RootGoalContract`，恢复 checkpoint
时也只从冻结的 `ProblemContract.exactStatement()` 重建。

Triage 返回 `semanticViewCandidate` 后，调用：

```java
semanticViewService.attach(rootGoal, frozenProblem, triage.semanticViewCandidate())
```

后续 scope、goal alignment、main goal obligation 和 proof-control goal 均使用：

```java
rootGoal.sourceStatement()
```

不使用上一轮 `englishStatement()`，也不允许 semantic view 覆盖 active main goal。

`PromptCatalog` 同时明确要求 CJK source 的英文视图只是 non-authoritative sidecar，
`preserves_*` 只是模型自述。

## 5. 测试优先证据

先新增测试、后修改生产代码。修复前专项测试结果：

```text
Tests run: 16, Failures: 11, Errors: 0, Skipped: 0
BUILD FAILURE
```

修复前明确暴露的问题包括：

- `all sufficiently large n` 没有产生稳定 `INDEX_SCOPE_MISMATCH`；
- `exists-forall` 交换为 `forall-exists` 后没有产生稳定量词次序错误；
- 正确公式后附加 arithmetic progression 解释没有被独立识别；
- 模型全报 `preserves_*=true` 时没有新的 deterministic findings；
- 20 轮测试在第一轮正确候选处就因旧的粗粒度语义识别而失败。

## 6. 新增和修复的测试

### 6.1 `ExactGoalContractCheckerTest`

使用同一个中文原题，覆盖：

| 场景 | 结果 | 主要错误码 |
| --- | --- | --- |
| 正确全局翻译 | PASS | `[]` |
| `ALL` 改成 `EVENTUAL` | BLOCK | `INDEX_SCOPE_MISMATCH` |
| 平移周期改成等差数列 | BLOCK | `CONCLUSION_SHAPE_MISMATCH` |
| 正确公式附加错误等价解释 | BLOCK | `MIXED_CONCLUSION_INTERPRETATION` |
| `exists-forall` 改成 `forall-exists` | BLOCK | `QUANTIFIER_ORDER_MISMATCH` |
| 固定见证改成 `T(n),L(n)` | BLOCK | `UNIFORM_WITNESS_SCOPE_MISMATCH` |
| 变量改名为 `b,k,P,Q` | PASS | `[]` |
| 普通题 `$x^2 >= 0$` | PASS | 新审计项均为 `not_applicable` |

纯等差数列候选没有提供量词和指标范围，因此 fail-closed 审计还会同时报告
`RECOGNIZED_SOURCE_UNPARSEABLE_TARGET`；主要的结论分类错误仍精确包含
`CONCLUSION_SHAPE_MISMATCH`。

### 6.2 `ProblemSemanticViewDeterministicAuditTest`

错误候选把四个模型自述字段全部设为 `true`：

```text
preserves_conclusion = true
preserves_domains = true
preserves_hypotheses = true
preserves_quantifiers = true
```

断言结果：

```text
status = rejected
deterministic_audit_passed = false
audit_findings contains index_scope and conclusion_shape
```

### 6.3 `RootGoalContractMultiRoundTest`

固定一个 `RootGoalContract`，通过生产方法 `ProblemSemanticViewService.attach(...)` 连续处理
20 轮固定候选序列：

```text
正确 -> eventual -> 正确 -> arithmetic progression -> quantifier swap -> 正确
```

循环直到第 19 轮。测试不使用真实 Provider、DeepSeek API、Docker、Temporal 或数据库。

每轮断言：

- 根目标文本不变；
- 根目标哈希不变；
- 错误候选为 `rejected`；
- 正确候选为 `usable`；
- `authoritative` 始终为 `false`；
- rejected sidecar 不进入下一轮 active `ProblemContract`。

### 6.4 `ProblemSemanticViewParityTest`

删除多个名称共享同一个片段提取断言的名义测试结构，改为真实的
`SemanticCase` 参数化数据。不同测试名称现在使用不同 source、candidate、预期状态和
预期错误码。

## 7. 修复后专项测试输出

执行命令：

```powershell
.\mvnw.cmd -pl mathproofmesh-core -am `
  "-Dtest=ExactGoalContractCheckerTest,ProblemSemanticViewDeterministicAuditTest,RootGoalContractMultiRoundTest,ProblemSemanticViewParityTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

最终控制台诊断：

```text
GOAL CONTRACT DIAGNOSTIC
---------------------------------------------------------------
CASE                                   RESULT   FAILURE CODES
exact_global_translation               PASS     []
global_to_eventual                     BLOCK    [INDEX_SCOPE_MISMATCH]
translation_to_arithmetic              BLOCK    [RECOGNIZED_SOURCE_UNPARSEABLE_TARGET, CONCLUSION_SHAPE_MISMATCH]
correct_formula_wrong_explanation      BLOCK    [MIXED_CONCLUSION_INTERPRETATION]
exists_forall_to_forall_exists         BLOCK    [QUANTIFIER_ORDER_MISMATCH, UNIFORM_WITNESS_SCOPE_MISMATCH]
uniform_to_per_instance                BLOCK    [QUANTIFIER_ORDER_MISMATCH, UNIFORM_WITNESS_SCOPE_MISMATCH]
---------------------------------------------------------------
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

20 轮诊断：

```text
MULTI_ROUND_COUNT=20
ROOT_HASH_CHANGES=0
ROOT_GOAL_REPLACEMENTS=0
REJECTED_SIDECAR_LEAKS=0
```

## 8. Core 全回归

执行命令：

```powershell
.\mvnw.cmd -pl mathproofmesh-core -am test
```

结果：

```text
Tests run: 928, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Contracts 前置模块同时执行：

```text
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
```

## 9. 完整验证记录

执行：

```powershell
.\scripts\verify-all.ps1
```

完整脚本中的 Maven `clean verify`、所有模块编译和测试、SpotBugs、CycloneDX SBOM、
OWASP Dependency-Check、覆盖率及安全检查均执行到通过阶段。

第一次完整验证发现 `RootGoalContract` 使用普通字符串比较 SHA-256，被 SpotBugs 报告
`UNSAFE_HASH_EQUALS`。实现随后改为 `MessageDigest.isEqual(...)`，专项与 core 回归再次
全部通过，SpotBugs 不再报告该问题。

完整脚本最终仍停在仓库既有 Phase-17 性能门：

```text
python_sidecar_cold_warm regression ratio 1.355451 exceeds 1.20
Phase-17 performance gates failed with exit code 1
```

重复样本在约 `1.213898` 至 `1.435446` 间波动；该基准测量短生命周期 Python/SymPy
进程启动，本次 Java 语义审计修改不经过该路径。本次没有修改性能阈值、性能基线、
Python Sidecar 或验证脚本，也没有把验证生成的报告文件提交。

因此，验证结论为：

- 本问题专项测试通过；
- core 全回归通过；
- 全模块 Maven 构建、测试和 SpotBugs 通过；
- `verify-all.ps1` 整体未报告 `FULL VERIFICATION: PASS`，唯一最终阻塞项是上述既有
  Python sidecar 性能采样门。

## 10. Git 记录

实现提交的 diff stat：

```text
12 files changed, 1227 insertions(+), 50 deletions(-)
```

修改文件及目的：

| 文件 | 目的 |
| --- | --- |
| `mathproofmesh-contracts/.../ProblemContract.java` | 安全附加哈希绑定的 semantic sidecar |
| `mathproofmesh-core/.../ExactGoalContractChecker.java` | 提取并审计精确目标结构 |
| `mathproofmesh-core/.../RootGoalContract.java` | 冻结不可变根目标 |
| `mathproofmesh-core/.../ProblemSemanticViewService.java` | 合并确定性审计并控制 sidecar 准入 |
| `mathproofmesh-core/.../SemanticProfileService.java` | 补充双语量词和忠实任务表达识别 |
| `mathproofmesh-desktop/.../DesktopSolveCoordinator.java` | 接入真实 Triage 和后续主目标生产链 |
| `mathproofmesh-server/.../PromptCatalog.java` | 声明英文视图非权威、模型 flags 仅为自述 |
| `ExactGoalContractCheckerTest.java` | 直接目标漂移、变量改名和普通题回归 |
| `ProblemSemanticViewDeterministicAuditTest.java` | 验证全 true 不能绕过 |
| `RootGoalContractMultiRoundTest.java` | 20 轮根目标与 sidecar 泄漏回归 |
| `ProblemSemanticViewParityTest.java` | 将名义场景改成真实参数化场景 |
| `ProofControlParityScenarios.java` | 删除旧共享伪场景 |

提交后工作树为干净状态，未提交 `target`、日志、数据库文件、临时缓存或验证报告。

## 11. 验收结论

- [x] 正确英文翻译得到 `usable`；
- [x] `ALL -> EVENTUAL` 被拒绝；
- [x] 平移周期改成等差数列被拒绝；
- [x] `exists-forall -> forall-exists` 被拒绝；
- [x] 统一 `T,L` 改成 `T(n),L(n)` 被拒绝；
- [x] 正确公式附加错误等价解释被拒绝；
- [x] 四个 `preserves_*` 全为 `true` 仍不能绕过；
- [x] 变量改名后的同类公式通过分类；
- [x] 普通非序列题不被误伤；
- [x] 20 轮根目标哈希和文本均未改变；
- [x] 20 轮没有 rejected sidecar 泄漏；
- [x] 专项测试通过；
- [x] core 全回归通过；
- [x] 未修改本次范围外的其他架构问题。
