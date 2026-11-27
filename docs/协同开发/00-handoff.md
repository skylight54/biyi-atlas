# 00 基线交接

首批三个功能分支从 `foundation-00-v1` 出发。可用 `git rev-parse foundation-00-v1^{commit}` 取得确切提交号。[原始导入](https://github.com/skylight54/biyi-atlas/commit/8960e0907eafa0818e522ec74631aed5e3297c3f) 是改动前的对照点，其 [212 项文件指纹](baseline.sha256) 已核对。

## 给三个开发对话

以下目录是同一父目录下的独立克隆，每个克隆具有自己的 Git 索引、构建目录和运行目录。在另一台机器上可用 `git clone --branch feat/01-hud https://github.com/skylight54/biyi-atlas.git 01-hud`，另两组替换分支名和目录名即可。

| 目录与分支 | 可复制的开工指令 |
| --- | --- |
| `01-hud` · `feat/01-hud` | 阅读本页和 [01 任务卡](01-hud.md)。从空手也显示的右上角 HUD 开始，在个人设置中接入位置与倍率，验证出区再回来和重登。 |
| `02-player` · `feat/02-player` | 阅读本页和 [02 任务卡](02-player.md)。从 PlayerAtlasScreen 接入区域与总览，打通点击空白处放私人路标、点地标看小卡片、解锁后回看进度。 |
| `03-editor` · `feat/03-editor` | 阅读本页和 [03 任务卡](03-editor.md)。从 EditorAtlasScreen 接入区域树和对象属性，复用已有编辑、撤销与预览操作，验证未保存的编辑返回流程。 |

各组按任务卡交付提交、测试与公共接线需求。共用父屏幕和事件接线由 09 汇总。三个分支使用各自的测试世界副本。

## 已接入的代码

以下包名以 `com.biyi.atlas` 为根。

| 入口 | 调用约定 |
| --- | --- |
| `client.AtlasScreen.openForMapKey()` | 默认 M 入口。创造模式且服务端已确认编辑权时打开 EditorAtlasScreen，其余打开 PlayerAtlasScreen。显式玩家预览继续走玩家视图。权限确认前画布显示等待提示。 |
| `client.PlayerAtlasScreen`、`client.EditorAtlasScreen` | 已接入现有屏幕工厂。子类可用父类的 `renderMapContent(graphics)`、`viewport()`、`centerOnPlayer()`、`switchViewArea(area)`、`updateWaypoint(waypoint,name,description)` 和 `moveEditHistory(redo)`。编辑历史沿用原有权限和未完成线段检查。 |
| `map.MapViewport(width,height,centerX,centerZ,pixelsPerBlock)` | GUI 像素，正 X 向东、正 Z 向南。使用 `screenX/screenY`、`worldX/worldZ` 转换。鼠标点按使用原始 GUI 坐标；栅格采样在整数像素加 0.5 后反投影。 |
| `client.AtlasScreen.drawPlayerCanvas(graphics,dimension,layer,camera)` | 在调用方已绑定的离屏目标上绘制玩家内容。01 持有专用 PlayerAtlasScreen 与自己的镜头。旧手持入口已复用这个画布。 |
| `client.AtlasReadView` | `regions(audience,dimension,layer)` 返回这一层允许列出的区域，`elementVisible(audience,kind,regionId,dimension,layer,x,z)` 检查元素在当前范围能否显示。编辑者中的玩家预览应传入 PLAYER。 |
| `client.AtlasScreen.openDisplaySettings()` | 玩家和作者左上“设置”进入同一个 AtlasDisplaySettingsScreen，作者原工程设置位于“工程”。01 接管个人设置页面与偏好迁移。 |
| `client.AtlasMapSession.baseMapStatus()` | 返回 `map.AreaMapStatus`，包含区域、阶段、样本数、说明。阶段区分无区域、请求中、未发布、部分及完整。它反映底图加载，发布按钮应等待服务端写入回执。 |
| `map.TerrainTilePos.atBlock(x,z)`、`map.AreaPublishReceipt` | 前者把方块坐标映射到旧底图 128 方块的空间索引。后者供 03/05 交换操作 ID、区域、结果、当前版本和原因。05 负责实现网络传输、持久化和冲突重试。 |

现有个人路标跟随 `client.AtlasStorage`，位于游戏实例的 `biyi_atlas/data/<世界键>/<维度命名空间>_<维度路径>.nbt`，例如 `minecraft_overworld.nbt`。世界键区分单人存档或多人连接。02 的跨账号验收还要考虑同一实例复用不同登录账号的情况。

## 回归与实机证据

- [合成样例](../../src/test/resources/foundation/atlas.json)提供海湾、锁定子区、分开的地标锚点和入口、已知与缺失瓦片、仅地表和底图回退，以及同一初始版本的成功与冲突两种回执。[FoundationChecks](../../src/test/java/com/biyi/atlas/map/FoundationChecks.java) 随 `verifyTerrain` 运行。回执用例约束 05 的输入输出；真实网络提交的并发性由 05 验收。
- 基线验证覆盖 Forge 构建、负坐标与角色规则、窄屏导航布局、旧地形 NBT、GLSL 云墨合成、MCP 桥接和旧工程往返。工程 ZIP 在副本内换世界名后核对 15 个载荷文件，并重放删除记录。复现命令见 [09 集成卡](09-integration.md)。
- 旧版实机对照留在维护者本地，分别是作者区域属性页和玩家全区云墨图。仓库提供 [15 张合成预览](../地图界面设计/公开预览/README.md)。本次代码验收采用离线构建和回归；新 M 键、设置返回、角色切换、两账号联机和帧时由 09 在整合包内复验。
