# 避役图志 Biyi Atlas — 设计说明

给「避役科」RPG 整合包用的地图模组，参考 Antique Atlas（古式地图）的手感：
**玩家探索自动绘制 + 羊皮纸风格 + 整合包作者可自定义**。

- 目标版本：Minecraft **1.20.1** / Forge **47.4.8**
- 运行侧：客户端功能为主（物品 + 全屏界面 + 存档数据），服务端只需加载模组
- 工程目录：`D:\DSH开发\biyi-atlas`（mod id: `biyi_atlas`）
- 测试实例：`F:\避役这一块\避役科工作端\.minecraft`（部署到其 `mods` 目录）

## 一、玩法循环

1. 玩家在任意维度走动 → 客户端按区块记录「已探索」，采样生物群系与高度
2. 手持 `biyi_atlas:atlas`（羊皮纸地图）→ 右键打开全屏地图
3. 地图界面：拖拽平移、滚轮缩放、维度标题、玩家位置箭头、已探索区域着色
4. 后续版本：路标点（waypoint）增删、地图导出为图片、他人地图抄录

## 二、作者自定义（整合包作者可改）

放在资源包 `assets/biyi_atlas/atlas/` 下，缺省值随模组内置：

| 文件 | 作用 |
| --- | --- |
| `style.json` | 羊皮纸底纹、缩放范围、纸张边缘留白、预置已探索区域 |
| `biomes.json` | 生物群系 → 羊皮纸配色 / 纹理的映射表 |
| `markers.json` | 作者预置标记点（村庄、副本、传送点） |

纹理：`assets/biyi_atlas/textures/atlas/paper.png`、`arrow.png`、`markers/*.png`

## 三、技术要点（1.20.1 Forge）

- 物品：`AtlasItem`，`use()` 时在客户端打开 `AtlasScreen`
- 探索采集：客户端 tick 事件，按玩家周围半径采样区块；每个区块存
  「是否已探索 + 代表色（生物群系叶色/水色 + 高度明暗）」
- 存档：`<gameDir>/biyi_atlas/data/<世界标识>/<维度>.dat`（NBT，位图 + 调色板）
- 渲染：`Screen` 中铺羊皮纸底纹，已探索区块按调色板绘制色块并叠加纸纹/描边，
  玩家箭头按 `player.getYRot()` 旋转
- 缩放/平移：独立相机状态，边界夹取在已探索范围

## 四、里程碑

- [x] M0 工程骨架 + ForgeGradle 链路跑通（build 出 jar）
- [ ] M1 物品 + 空的全屏羊皮纸界面（可开关）
- [ ] M2 探索采集 + NBT 存档 + 区块着色渲染
- [ ] M3 缩放/平移/玩家箭头/维度切换
- [ ] M4 作者配置文件（style/biomes/markers）+ 内置默认资源
- [ ] M5 部署到 `F:\避役这一块\避役科工作端\.minecraft\mods` 并启动验证

## 五、环境备注

- 本机系统级 TLS（SChannel）异常，命令行 `curl`/`Invoke-WebRequest` 走 HTTPS 会失败；
  **Java 自带 TLS 正常**，因此 Gradle/ForgeGradle 下载依赖不受影响
- JDK 17：`D:\java\jdk17`；Gradle 8.8：本地 wrapper 缓存
- 沙箱限制：工作区 `D:\DSH开发` 内可写；`~/.gradle`、`F:\...` 默认不可写
  → Gradle 用户目录改用 `D:\DSH开发\.gradle-home`；部署到实例时单独申请权限
