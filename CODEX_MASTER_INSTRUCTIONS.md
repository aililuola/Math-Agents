# JavaMathProofMesh-0.8.0 — Codex 主执行指令

你现在负责把现有 MathProofMesh Python 快照迁移为 Java-first 版本。必须把本文件当作执行入口，并同时读取下列同目录文件：

1. `MIGRATION_PLAN.md`：最高优先级、完整架构与阶段规范；
2. `PHASE_GATES.yaml`：机器可读的阶段依赖、输出和门禁；
3. `PYTHON_SOURCE_MIGRATION_MAP.csv`：142 个源文件/资源的逐项归宿；
4. `PYTHON_TEST_MIGRATION_MAP.csv`：167 个测试/测试支持文件（164 个测试模块）、707 个显式测试函数的逐项归宿；
5. `OPS_CONFIG_DOC_MIGRATION_MAP.csv`：92 个配置、文档、基准、打包、脚本、许可证和项目元数据文件的逐项归宿；
6. `SOURCE_SNAPSHOT_SHA256SUMS.txt`：按计划书固定算法生成的 401 行逐文件 SHA-256 基线。

三份映射 CSV 的源路径并集必须恰好覆盖排除 `.git` 后的 401 个源快照文件，并与第 6 份清单逐路径、逐哈希一致。

若文件之间出现表述差异，优先级严格为：

```text
用户当前明确指令
> 完整分阶段迁移计划书
> 阶段门禁清单 YAML
> 源码迁移映射 CSV
> 测试迁移映射 CSV
> 运维配置文档迁移映射 CSV
> 源快照文件清单（完整性证据，不覆盖计划书的语义规则）
> 你自己的实现偏好
```

不得用自己的偏好覆盖既定选择。不得要求用户重新决定计划书已经确定的 JDK、Spring Boot、数据库、模块边界、侧车协议或阶段次序。

---

## 一、固定目标和写入边界

1. 找到包含原 Python 项目的 Git 工作树根目录，将其记为 `WORKSPACE_ROOT`。它应包含 `pyproject.toml`、`BUILD_INFO.json`、`src/mathproofmesh/` 和 `tests/`。
2. 目标根目录固定为：

```text
TARGET_ROOT = WORKSPACE_ROOT/JavaMathProofMesh-0.8.0
```

3. 只允许在 `TARGET_ROOT` 内新增、修改、删除文件。允许在工作树根新增且仅新增这一个顶层目录。
4. 不得修改、格式化、移动、重命名或删除任何原 Python 文件、测试、文档、配置、运行输出或 Git 元数据。
5. 不得在 `TARGET_ROOT` 外产生 `.venv`、`__pycache__`、`.pytest_cache`、日志、临时文件、数据库卷、`target/`、构建输出或 IDE 文件。
6. 需要 Python 资源时，只读源文件并复制到 `TARGET_ROOT`，同时记录来源路径与 SHA-256。生产运行不得导入原 Python 包。
7. 第 0 阶段必须先冻结原始文件集合；之后每个阶段开始和结束都复算该集合，并检查目标目录之外没有新增文件。

一旦检测到原文件变化，立即：

- 停止当前阶段；
- 将阶段状态写为 `blocked`；
- 不自动恢复、覆盖或 `git checkout` 用户文件；
- 在阶段报告中列出精确路径、旧/新哈希和建议恢复命令；
- 等待用户处理。

---

## 二、执行模式

默认每次只执行 `migration/state.json` 指向的**下一个未通过阶段**，完成阶段报告后停止。除非用户明确要求连续执行多个阶段，否则不得越过阶段边界。

执行算法：

```text
读取六份规范
→ 定位 WORKSPACE_ROOT/TARGET_ROOT
→ 确认当前阶段及其前置阶段全部 passed
→ 运行源不可变检查
→ 将当前阶段标记 in_progress
→ 只实现当前阶段范围
→ 移植/编写测试
→ 执行阶段命令和全量 verify
→ 再次检查源不可变
→ 更新 source-state.csv / test-state.csv / auxiliary-state.csv
→ 生成 phase-XX.md
→ 全部门禁 PASS 才标记 passed 并提交
→ 停止，向用户报告证据
```

任何门禁失败时，不得：

- 跳过测试；
- 用 `@Disabled`、删除测试、放宽断言或吞异常使构建变绿；
- 以 `TODO`、`FIXME`、空返回、硬编码成功或 `UnsupportedOperationException` 代替必需功能；
- 提前实现下一阶段来掩盖当前阶段边界不清；
- 自行更换固定框架版本；
- 只报告“基本完成”。

状态只能是 `pending`、`in_progress`、`passed` 或 `blocked`。

---

## 三、不可更改的技术决定

- Java 25；推荐 Eclipse Temurin 25 当前安全补丁；
- Apache Maven 3.9.16；Apache Maven Wrapper 3.3.4 `only-script`，固定分发校验和；第 0 阶段用经官方 SHA-512 核验的临时 Maven 3.9.16 调用全限定 Wrapper Plugin 目标生成，不依赖用户全局 Maven；
- Spring Boot 4.1.0；Spring Modulith 2.1.0；
- PostgreSQL 18.4 作为权威状态存储，通过 Docker Compose 运行；
- Temporal Java SDK 1.37.0；本地开发/CI 固定使用第 0 阶段锁定 digest 的 `temporalio/temporal:1.8.1`（内嵌 Server 1.31.2），仅在第 13 阶段接入；服务必须 headless、SQLite 持久化、只绑定 `127.0.0.1:7233`，且不得作为生产部署方案；
- 模块化单体，不拆微服务；
- v0.8.0 只允许 `MathProofMeshSolveWorkflow` 和 `RouteExplorationWorkflow` 两类 Workflow；最终盲审使用幂等 `FinalReviewActivity`，禁止自行增加可选 `FinalReviewWorkflow`；
- Spring JDBC/JdbcClient + Flyway；不使用 JPA/Hibernate；
- `flyway-core` 与 `flyway-database-postgresql` 必须同时声明；
- JDK `HttpClient` 直接实现 Provider Adapter；首版不使用 Spring AI；
- PostgreSQL Transactional Outbox/Inbox；首版不引入 Kafka、Redis、RabbitMQ、Neo4j；
- JavaFX 25.0.4 桌面端；Windows 秘密使用 JNA/DPAPI；
- SymPy/Z3 兼容能力放在 `TARGET_ROOT/python-compute-service`，通过版本化 stdio JSON-RPC；
- 侧车不能监听网络、不能访问数据库、不能读取 API key、不能修改工作树；
- 模型生成的任意 Python 默认关闭，只能在明确启用的固定 digest 容器沙箱中运行；
- 所有外部输入未知字段失败，核心领域禁止 `Map<String,Object>`；
- 使用不可变 `record`、`sealed interface`、`enum` 和显式状态机；
- 虚拟线程用于 I/O，但必须配合有界并发、预算、RPM、超时和运行租约。

这些决定只能在官方依赖不可解析、明确安全漏洞或无法通过兼容性 smoke test 时通过 ADR 变更。ADR 必须说明证据、影响和替代版本；在用户批准前阶段保持 `blocked`，不能自行降级。

---

## 四、代码质量优先级

优先级严格为：

```text
安全、数学正确性及恢复/一致性不变量（不可妥协）
> 高效、简洁、可维护（同等安全正确时采用更快更短的成熟方案）
> 完成整个迁移并保持功能完整与兼容（最终完成条件）
> 非必要扩展
```

实现时优先使用成熟的 JDK/Spring/PostgreSQL 能力，避免重复造轮子；但不得为了减少代码而删减验证、负面记忆、反例传播、消息回执、Proof Control、Inspiration、恢复和 exactly-once 语义。功能完整不是可牺牲的低优先项，而是最终验收条件。

不得制造一个对应 `orchestrator.py` 的超大型 `Orchestrator.java`。必须按计划书拆成契约、领域、应用服务、适配器和 Workflow。

---

## 五、测试和证据要求

1. Python 基线必须在隔离环境中达到 `759 passed`。若本机收集数不同或失败，阶段 0 阻塞。
2. 每个 Python 测试函数至少映射到一个同语义 JUnit 测试、参数化 case 或明确的跨语言差分用例。
3. 每个阶段都运行：

Windows：

```powershell
.\scripts\check-original-immutable.ps1
.\mvnw.cmd -B -ntp verify
```

Linux/macOS：

```bash
./scripts/check-original-immutable.sh
./mvnw -B -ntp verify
```

4. 涉及 PostgreSQL 的阶段必须使用真实 PostgreSQL Testcontainer，不以 H2 替代。
5. 涉及 Temporal 的阶段必须包含 replay、重复投递、Worker 崩溃和 Activity 完成后未确认等故障测试。
6. Provider 测试只使用 Mock/录制 fixture；除非显式设置 `MPM_ALLOW_LIVE_PROVIDER_CALLS=true`，不得调用付费模型。
7. 日志、异常、SSE、Artifact 和测试快照必须扫描 secret、Bearer token、API key 及不应持久化的私有推理文本。
8. `source-state.csv`、`test-state.csv` 和 `auxiliary-state.csv` 中，只有对应实现、复制/转换、测试、差分和门禁全部通过后才能改为 `verified`；三表必须分别保持 142、167、92 行。

---

## 六、Git 规则

若当前目录属于 Git 仓库：

1. 不修改 `main`；
2. 新建分支 `feature/java-mathproofmesh-0.8.0-migration`；若该功能分支已存在，继续使用，不另建重复分支；
3. 只提交 `JavaMathProofMesh-0.8.0/`；
4. 不提交训练/求解运行输出、数据库卷、Temporal 数据、秘密、raw provider response、缓存或打包临时文件；
5. 每个通过门禁的阶段形成一个独立提交：

```text
feat(java-migration): complete phase XX <phase-name>
```

6. 未通过门禁不得提交“完成”状态；可以保留本地工作区并报告阻塞。

---

## 七、阶段报告与对用户的输出格式

每阶段必须生成：

```text
TARGET_ROOT/migration/reports/phase-XX.md
```

并按以下格式回复用户：

```markdown
## Phase XX — PASS / BLOCKED

- 源不可变：PASS/FAIL（before/after manifest hash）
- 本阶段范围：...
- 已迁移源文件：X 个
- 已映射测试文件/用例：X / Y
- 构建：PASS/FAIL（命令）
- 单元测试：X passed
- 集成测试：X passed
- 差分测试：X passed
- 安全检查：PASS/FAIL
- 关键新增文件：...
- Git 分支/提交：...
- 阶段报告：...
- 下一阶段：Phase YY（仅说明，不提前执行）
```

不得省略失败记录。不得输出虚构的测试数量、命令或提交哈希。

---

## 八、现在开始时的第一项任务

若 `TARGET_ROOT/migration/state.json` 不存在，只执行**第 0 阶段：不可变基线、环境和框架预检**。

第 0 阶段完成前不得写 Java 业务逻辑。必须先：

- 确认源快照；
- 冻结原始文件清单；
- 验证 JDK/Git/Docker/Python；下载并核验官方 Maven 3.9.16 临时分发包，再用全限定 Maven Wrapper Plugin 3.3.4 目标生成 `only-script` Wrapper；
- 在目标内建立隔离 Python 基线环境；
- 达到 759 passed；
- 导出 Schema、枚举、默认配置和 hash vectors；
- 生成 source/test/auxiliary 三份迁移状态 CSV，并证明其 401 条源路径与冻结清单完全一致；
- 通过预检 POM 验证全部固定 Maven BOM、库、插件、JavaFX 平台构件、PostgreSQL/Flyway/Temporal 组合可解析且无冲突；同时锁定 `postgres:18.4-bookworm` 与 `temporalio/temporal:1.8.1` 的完整不可变 digest，核验 Temporal CLI 1.8.1 和内嵌 Server 1.31.2；
- 写 phase-00 报告。

完成后停止，等待用户指示继续第 1 阶段。若任何前置条件缺失，给出精确安装项和核验命令，不要修改或绕过标准。
