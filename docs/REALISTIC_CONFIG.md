# 行情配置与进世界问题排查

配置位置：`config/nh-kantan/nhkantan.cfg` 的 `realistic` 分类。保留原文件与世界的 `nhkantan/realistic.dat`；不需要为了更新而删除它们。GTNHLib 会补充新增字段，下面的归一化只改变本次运行的有效值，不主动重写整份 cfg。

## 本次修复

市场启动不再把普通配置/账本加载异常传播到整个世界启动流程。能安全归一化的配置会采用修正值并在日志中说明；无法安全加载的账本仍拒绝交易，世界继续加载，登录和打开终端会提示市场暂停。已迁移世界关闭 realistic 开关时也只暂停市场，不启动另一份旧账本。

已处理的配置组合：

- 最短阶段时间大于最长时间：交换两端；单个值超出注解范围或不是有限数时使用该字段默认值。
- 维持保证金高于初始保证金：按初始保证金的一半处理。
- 机器人五类配置缺失、长短不一致或某项非法：保留有效项，补齐/修复其余项；行动间隔至少 100 ms。
- 多品种的机器人总量超过 500：按预算缩减机器人数量。

机构限额低于已有仓位、改变存档品种集合、损坏账本等不属于可以任意改数的情况：保留文件并暂停市场，日志保留原始原因。请恢复对应设置或提供日志进一步处理，不要靠删除账本解除限制。本次未取得完整的真人崩溃报告，实际进世界结果仍需人工验证。

## 行情模型和参数

保留用户新提交的独立基本价值过程：趋势、整理、回归与双向突破有各自的持续阶段，订单流只产生短期偏移，不逐步累积到基本价值。公允参考是该过程加有界 VWAP 反馈及平滑噪声；实际成交价、K 线继续由订单簿产生。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `regimeMinSeconds` / `regimeMaxSeconds` | 30 / 75 | 一般阶段持续时间，突破较短 |
| `trendMoveBps` / `breakoutMoveBps` | 500 / 800 | 阶段预期幅度参数，100 bps = 1% |
| `fairAverageSeconds` | 60 | VWAP 桶窗口，还受当前历史保留长度限制 |
| `fairVolatilityTicks` × `volatilityMultiplier` | 0.5 × 12 | 有效扰动基准，单位为价格 tick |
| `anchorFollowRate` | 0.001 | 中期基本价值锚点每步跟随速度 |
| `flowFollowRate` | 0.22 | 临时订单流偏移每步跟随速度 |
| `maxFlowImpactBps` | 20 | 临时订单流偏移的最大幅度 |
| `flowImpact` | 0.02 | 上述最大幅度的使用比例；本次明确为无量纲比例 |
| `fairVwapWeight` | 0.15 | 有界 VWAP 偏移的权重，范围 0–1 |
| `vwapDeviationVolMultiplier` | 2 | VWAP 偏移限幅，单位为有效扰动基准的倍数 |
| `fairNoiseMemory` | 0.85 | 噪声平滑记忆系数，小于 1 |
| `fairNoiseLimitMultiplier` | 3 | 平滑噪声限幅倍数 |
| `institutionalCapitalMultiplier` | 100 | 原机构资金的放大倍数，增加部分只补发一次 |
| `institutionalOrderMultiplier` | 100 | 原基础下单数量的放大倍数 |
| `institutionalPositionLimit` | 100000 | 机构仓位上限，不改变玩家上限 |

## 此前版本的 Python 验证工具

```bash
python3 tools/simulate_market.py
python3 tools/simulate_market.py --config /path/to/nhkantan.cfg
# 也支持 JSON 参数覆盖；默认参数从 RealisticConfig.java 的注解读取。
```

当前用户要求回到 `9f8f1e5` 行情过程，仅保留做市 3 倍、巨鲸仓位 1/3、五类双实例三项差异。本次未运行模拟；下列工具和历史统计不能作为当前组合已验证的证据。

报告在 `build/reports/python-market/`（指定配置时可用 `--output` 另选目录），包含参考序列 CSV、阶段切换、有效配置和摘要。默认验证 16 个种子、各 30 分钟，覆盖六种状态、序列化恢复和非法配置归一化。VWAP 反馈使用 Python 参考序列和代理成交量，不等同于 Minecraft 内的完整撮合验证。

本轮 Java 只执行 `bash gradlew --console=plain spotlessApply assemble`。没有运行 JUnit、runServer 或 runClient，最终进入存档和交易体验由用户验证。

## 当前生效版本：9f8f1e5 行情基线与三项调整

公允价过程、阶段路径、方向信号、报价偏移、机器人市价与期权对冲执行均恢复到 `9f8f1e5`。不使用后加的共同报价中心、重报价速度、机器人报价带/寿命、滑点/吃单比例保护或短期相关扰动。旧 cfg 中这些字段不再影响市场，不需要删除整份配置。

| 项目 | 当前行为 |
| --- | --- |
| `makerQuoteSizeMultiplier` | 默认 3；旧值 10 在内存迁移为 3，其他自定义值保留 |
| 做市挂单 | 基线单档数量的 3 倍，再按快慢实例乘 0.8/1.2，默认约 720–3240 张/档；深度档数不变 |
| 做市频率 | 回到基线 500ms 基准，分别乘 0.85/1.15，再叠加原有抖动与动作概率；不再乘 0.4 |
| 巨鲸最大仓位 | `max(1, institutionalPositionLimit / 3)`，默认 33333 张；其他机构默认 100000 张 |
| 旧巨鲸超额仓位 | 保留持仓，只减不加，账户预留限额随减仓逐步收紧；不因新限额阻断启动 |
| 巨鲸单次下单 | 沿用基线数量公式、突破放大和双实例规模，受新仓位上限约束；移除旧的单次 5000 张硬限 |
| `botCounts` | 运行时固定为 [2,2,2,2,2]，仍受全市场 500 实例预算限制 |

两实例分别采用节奏 0.85/1.15、下单规模 0.8/1.2 和独立随机流；趋势窗口为 3–8/8–17 根，回归窗口为 5–12/12–23 根，噪声市价概率为 65%/40%。恢复旧存档时取消已停用（编号 >=2）的机器人委托，玩家订单与账户保留。AE 通知与 UI 保持当前版本。

仅执行 `bash gradlew --console=plain spotlessApply assemble`，不运行 Python 模拟、Java 测试、runServer 或 runClient。产物 `build/libs/nhkantan-market-9f8f1e5.jar`，最终体验由用户确认。
