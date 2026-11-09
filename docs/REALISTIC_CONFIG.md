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

## Python 先行验证

```bash
python3 tools/simulate_market.py
python3 tools/simulate_market.py --config /path/to/nhkantan.cfg
# 也支持 JSON 参数覆盖；默认参数从 RealisticConfig.java 的注解读取。
```

报告在 `build/reports/python-market/`（指定配置时可用 `--output` 另选目录），包含参考序列 CSV、阶段切换、有效配置和摘要。默认验证 16 个种子、各 30 分钟，覆盖六种状态、序列化恢复和非法配置归一化。VWAP 反馈使用 Python 参考序列和代理成交量，不等同于 Minecraft 内的完整撮合验证。

本轮 Java 只执行 `bash gradlew --console=plain spotlessApply assemble`。没有运行 JUnit、runServer 或 runClient，最终进入存档和交易体验由用户验证。
