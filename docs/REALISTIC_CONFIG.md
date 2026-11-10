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

最新行情微调按用户要求未运行模拟，以下 Python 工具保留此前模型，尚未同步最新 Java 走势逻辑，不代表当前算法验证结果。

报告在 `build/reports/python-market/`（指定配置时可用 `--output` 另选目录），包含参考序列 CSV、阶段切换、有效配置和摘要。默认验证 16 个种子、各 30 分钟，覆盖六种状态、序列化恢复和非法配置归一化。VWAP 反馈使用 Python 参考序列和代理成交量，不等同于 Minecraft 内的完整撮合验证。

本轮 Java 只执行 `bash gradlew --console=plain spotlessApply assemble`。没有运行 JUnit、runServer 或 runClient，最终进入存档和交易体验由用户验证。

## 盘口加厚、去插针与双实例

默认五类策略各运行两个实例，原来的默认 `botCounts=[3,4,4,8,1]` 会在内存中自动迁移为 `[2,2,2,2,2]`；其他自定义数量保留。每类快/慢两个实例有不同的节奏（0.85/1.15 倍）、数量（0.8/1.2 倍）和随机流；趋势窗口分别为 3–8 / 8–17 根，回归窗口为 5–12 / 12–23 根，噪声市价概率为 65% / 40%，做市价差系数为 0.9 / 1.15。

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `makerQuoteSizeMultiplier` | 3 | 只把做市被动挂单再加厚 3 倍，普通主动单不一起放大 |
| `makerIntervalScale` | 0.4 | 原 500ms 基准约变为 200ms，再叠加快/慢实例节奏、抖动与工作线程粒度 |
| `whaleMaxOrderContracts` | 5000 | 巨鲸单次硬上限，包含突破期的放大后数量 |
| `makerSpreadBps` | 10 | 基础买卖总价差，随后加盘口档位距离 |
| `makerInventorySkewBps` | 5 | 库存偏移上限，并额外限制在半价差以内，避免大库存把报价推远 |
| `makerRepriceBpsPerSecond` | 120 | 共同报价参考每活动秒最多跟随约 1.2%；保留突破跟随能力 |
| `automatedQuoteBandBps` | 30 | 偏离共同参考的过时机器人挂单退出，另有最小 tick/档位宽度底线 |
| `automatedQuoteLifetimeSeconds` | 2 | 机器人被动挂单有效期；玩家 GTC 不受此项影响 |
| `botMaxSlippageBps` | 20 | 机器人/期权对冲 IOC 最差价保护，另有最小 tick/档位底线 |
| `botMaxLiquidityFraction` | 0.25 | 一次机器人主动单最多按估计对手盘可成交深度的 25% 下单 |

旧配置中的原默认倍率 10 会在本次运行中迁移到 3，不重写 cfg；其他自定义倍率保留。默认做市单目标约为 720–3240 张/档（两实例分别约 720–2160 / 1080–3240），受机构资金和限仓约束。趋势/突破阶段参数未降低；报价参考加速跟随到 120bps/秒，避免通过把市场压平来消除影线。上述保护约束单次报价和机器人订单，**不限制一段趋势的累计涨跌幅**。

机器人不再无保护扫到品种价格上下界，旧机器人挂单在恢复后重新报价；清理只识别确定性的机器人 UUID，不撤玩家挂单。撮合仍按真实对手价成交，OHLC 高低价不做裁剪。旧的插针历史会随新行情自然滚出窗口，通常需要约一两分钟。

此前 10 倍盘口版本的 Python 撮合对照：`PYTHONDONTWRITEBYTECODE=1 python3 tools/simulate_microstructure.py`。默认对照旧组合与新组合，4 个种子各 10 分钟，报告在 `build/reports/microstructure/`。按“单侧影线至少 30bps 且至少为实体 3 倍”统计，异常长影线 102→0；新组合每 10 分钟模拟成交约 878–1019 万张，较大实体幅度（95%分位）仍约 1.73%–2.03%。这不是 Java/MC 实测，模型未完整接入保证金/手续费账本；本轮仍只编译打包，交由用户人工体验。

## 最新走势微调

趋势目标从匀速直线改为缓入缓出，加入跨阶段保留的短期相关扰动，让趋势中可以有回撤和停顿。突破推进窗口由阶段前 22% 改为前 55%，预期幅度仍为原设置。方向压力平滑并在阶段首尾淡入淡出；做市方向溢价只保留少量基点，并独立平滑，不再叠加一大段阶段涨幅后在切换时突然撤回。真实成交与 OHLC 不做裁剪。

本次按用户要求直接修改 Java、只编译打包；未运行 Python、JUnit 或 MC，未给出新模拟统计，等待人工体验。
