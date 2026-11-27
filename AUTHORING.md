# 避役图志 · 作者自定义手册（章节式羊皮纸 RPG 地图）

地图是**作者手绘的羊皮纸地图**：世界按**章节分成若干区域**（用线条圈定），区域需要**许可证解锁**，
地图还按**深度分层**（0 层＝地表：路径与地点；-1/+1 层＝地下、室内、主城内部等）。

> 默认**不画探索色块**（`style.json` 的 `showTerrain: false`）。地图元素就是**线条 + 图标 + 文字**。

## 一、快速流程（游戏内用魔杖）

1. `/biyi_atlas wand`（管理员）拿地图魔杖 → 右键打开**作者模式地图**
2. `6` 区域工具：在地图上连续右键点出**区域边界**，`Enter` 闭合成区域
   （自动生成 `region_N`，许可证 id 同名；区域内的地点/道路会自动归到该区域）
3. `2` 地点 / `3` 传送点 / `4` 道路：在区域里放内容
4. `Shift+PageUp/Down` 切换**编辑层**：在 -1 层放"地下密道"、+1 层放"塔顶"等内容
5. `7` 解锁开关：右键区域，在"已解锁/未解锁"间切换，**预览**玩家视角的效果
6. `E` 导出：`config/biyi_atlas/exported/<世界>/content.json`，改名与许可证 id 就在这个文件里改
7. 玩家侧解锁：`/biyi_atlas unlock <区域id> [玩家]`，或发一张 `/biyi_atlas license <区域id> [玩家]`
   的**区域许可证**让玩家右键使用

## 二、区域：`regions.json`

```json
{
  "regions": [
    {
      "id": "ch1_valley",
      "name": "第一章 · 避役谷",
      "dimension": "minecraft:overworld",
      "layer": 0,
      "outline": [[-512,-512], [512,-512], [640,256], [128,768], [-640,384]],
      "lineColor": "5A4632",
      "fillColor": "7C8C5A",
      "fillAlpha": 40,
      "labelText": "第一章 · 避役谷",
      "labelX": 0,
      "labelZ": 0,
      "showOutlineWhenLocked": true,
      "unlock": { "type": "license", "id": "ch1_valley" },
      "defaultUnlocked": false
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `outline` | **折线多边形**（世界坐标 `[x,z]`，自动闭合），就是"可探索区域"的边界线 |
| `layer` | 该区域所在层；不同层的区域互不遮挡 |
| `unlock.type` | `license`（凭许可证，默认）/ `always`（默认开放）/ `advancement`（预留，需服务端授权） |
| `unlock.id` | 许可证 id（就是解锁命令里要传的值） |
| `fillColor` + `fillAlpha` | 区域淡填充（0 = 不填，只要线条就设 `fillAlpha: 0`） |
| `showOutlineWhenLocked` | 未解锁时是否仍画虚线暗边（提示"下一章在这里"） |
| `defaultUnlocked` | 第一章之类默认开放的区域设为 `true` |

**内容归属**：地点/道路的 `region` 字段填区域 id。`region` 为空的元素**不受解锁限制**，始终可见；
填了 id 的元素只有该区域解锁后才显示。

## 三、作者工具箱（大型建筑 → 地图上的"地点图"）

地图不画探索色块，而是**把关键地点做成一张手绘俯视图贴到地图上**。三种来源，全部会变成一个
**地图元素**（玩家可点击、可追踪；作者可编辑名字/描述、可删除）：

| 工具（底部「放置」页按钮） | 作用 |
| --- | --- |
| **生成俯视图** | 以你为中心采样地表（屋顶视角），做旧纸色调 + 墨线勾边，存成地点图并登记 |
| **±64 / ±128 / ±256** | 生成半径（点一下循环切换） |
| **框选** → **生成(框选)** | 先在地图上点两个对角，只生成这一块（适合远景建筑） |
| **旅行图 day/topo/biome/Y=…** + **换旅行图层** | **从旅行地图导入**（见下），点按钮导入；`Shift+点击`或「换旅行图层」切换图层 |
| **导入外部图** | 把你自己的 PNG 挂到当前位置（见下） |
| **扫描NPC** | 附近 NPC 一次性标成路标（可选的偷懒操作，默认把 CNPC NPC 当传送点候选） |
| **屋顶/地面、墨线开/关、羊皮纸/原图** | 俯视图质量选项 |

### 3.1 从旅行地图导入（推荐，含不同 Y 轴切面）

已实测你机器上 JourneyMap 5.10.3 的磁盘布局：

```
journeymap/data/sp/<存档文件夹名>/<维度>/<图层>/<区域X>,<区域Z>.png
```

- 每张 PNG = **512×512 像素 = 512×512 方块**（1 像素 = 1 方块）
- 图层目录：`day`（白天地表）/ `night` / `topo`（地形）/ `biome`（生物群系）
- **数字目录（`0` `2` `3` … `9`）= 不同 Y 轴切面** → 这就是"分层俯视图"，比如把 Y=9 的地下通道导成一张图

> 想拍某个建筑：先在旅行地图里站到建筑上转一圈让它把图缓存好，再回游戏点「旅行图 day」导入。

### 3.2 外部图片导入

把你画好的（或别的工具导出的）PNG 放进：

```
<游戏目录>/biyi_atlas/authoring/<世界>/import/
```

然后在地图「放置」页点 **导入外部图**，图片会以你当前位置为中心贴上去（默认 1 像素 = 4 方块；
多项时按文件名排序取第一张，删掉/换名即可换图）。

### 3.3 连路

用地点图把关键建筑标完后，按 `R` 开始记录轨迹，走一遍这条路，再按 `R` 结束，
就会生成一条道路把地点连起来（道路画在地点图之上）。

## 四、作者手绘地图图层：`maps.json`

**这是"解锁后看到预设地图"的主角**：你可以在 PS/画图软件里画好一整张羊皮纸地图，按世界坐标贴上去；
属于某个区域的地图图层，只有该区域解锁后才显示。

```json
{
  "maps": [
    {
      "id": "ch1_drawn",
      "dimension": "minecraft:overworld",
      "layer": 0,
      "region": "ch1_valley",
      "texture": "biyi_atlas:atlas/map/demo_ch1",
      "originX": -512,
      "originZ": -512,
      "width": 512,
      "height": 512,
      "blocksPerPixel": 4,
      "alpha": 255
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `texture` | `命名空间:atlas/map/文件名`（放到资源包 `assets/<命名空间>/textures/atlas/map/`），也接受完整 `textures/...png` |
| `originX` / `originZ` | 图片**左上角**对应的世界坐标 |
| `width` / `height` | PNG 的像素尺寸 |
| `blocksPerPixel` | 1 像素等于多少方块 → 决定缩放。上例 512px × 4 = 2048 方块见方 |
| `alpha` | 0~255，做旧/淡化；多张图层可叠加 |
| `region` | 归属区域（空 = 始终显示） |
| `layer` | 深度层，与其它元素一致 |

示例图已随模组附带：`assets/biyi_atlas/textures/atlas/map/demo_ch1.png`（用 `python tools/gen_map_demo.py` 生成），
把上面那段 JSON 放进 `config/biyi_atlas/maps.json` 就能立刻在地图上看到。

> 调研佐证：主流模组（FTB Chunks / Open Parties and Claims）**都不做多边形填充**，而是"位图/整图贴 + 折线描边"。
> 所以本模组的推荐做法就是：**地图主体用你画的 PNG，线条（区域边界、道路）用折线，图标用 32×32 的 marker 图**。

## 四、图层与元素

所有元素（区域/地点/道路）都有 `layer`：`0` 地表（默认）、`-1` 地下、`+1` 上层、`-2` 更深处……

```json
{
  "places": [
    { "name": "避役主城", "type": "poi", "dimension": "minecraft:overworld",
      "layer": 0, "region": "ch1_valley", "x": 120, "z": -340,
      "icon": "town", "color": "8C4A44", "description": "主城", "alwaysLabel": true },
    { "name": "主城 · 地下密室", "type": "poi", "dimension": "minecraft:overworld",
      "layer": -1, "region": "ch1_valley", "x": 128, "z": -332,
      "icon": "quest", "color": "3E6E9E", "description": "只有拿到许可证后才会出现在地图上" }
  ],
  "roads": [
    { "name": "主城大道", "dimension": "minecraft:overworld", "layer": 0, "region": "ch1_valley",
      "width": 3, "color": "8C6F52", "dashed": false, "points": [[120,-340], [340,-300], [560,-120]] }
  ]
}
```

内置图标名（`icon`）：`pin` `flag` `portal` `town` `village` `camp` `tower` `ruin` `shop` `quest` `danger`
（对应 `assets/biyi_atlas/textures/atlas/marker/<icon>.png`，可自行加图；名字写错会退回 `pin`）。

## 四、玩家侧的键盘操作

| 键 | 作用 |
| --- | --- |
| 右键（普通地图） | 打开全屏地图 |
| 左键点地点/路标 | 设为**追踪目标**，关掉地图后 HUD 顶部显示箭头与距离 |
| `PageUp` / `PageDown` | 切换**查看层**（地表 / 地下 / 上层…） |
| `[` / `]` | 切换维度 |
| `C` | 回到自己位置 |
| 拖拽 / 滚轮 | 平移 / 朝光标缩放 |

作者模式另有：`1`~`7` 切换工具、`Enter` 闭合区域或结束道路、`Shift+右键` 删除、
`R` 轨迹记录生成道路、`E` 导出、`Shift+PageUp/Down` 切编辑层。

## 五、解锁

| 方式 | 用法 |
| --- | --- |
| 命令 | `/biyi_atlas unlock <区域id> [玩家]`（管理员；CNPC 脚本/任务链可直接调用） |
| 许可证物品 | `/biyi_atlas license <区域id> [玩家]` 发放 `区域许可证`，玩家右键使用即解锁（物品消耗） |
| 作者预览 | 魔杖工具 `7`，右键区域切换解锁状态 |

解锁状态按世界存在客户端 `<gameDir>/biyi_atlas/unlocked/<世界>.json.nbt`，随存档走。

## 六、样式：`style.json`

```json
{
  "title": "避役科 · 羊皮纸地图",
  "showTerrain": false,
  "zoomDefault": 2.0, "zoomMin": 0.6, "zoomMax": 10.0,
  "colors": { "1": "6E7F55", "11": "8B8B84" },
  "prerevealed": [],
  "markers": []
}
```

- `showTerrain: true` 才会把"玩家探索过的地形色块"画成底图（默认 false，手绘地图不需要）
- `colors` / `prerevealed` / `markers` 保留给需要地形底图或静态标记的作者

## 七、内容来源（四层，后者覆盖前者）

1. 模组内置 `assets/biyi_atlas/atlas/{regions,places,roads}.json`
2. 资源包同名路径
3. `config/biyi_atlas/{regions,places,roads}.json`
4. 游戏内魔杖编辑 `<gameDir>/biyi_atlas/authored/<世界>/content.json`

## 八、命令总览

```
/biyi_atlas open [玩家]              打开地图
/biyi_atlas give [玩家]              发放地图物品
/biyi_atlas wand [玩家]              发放地图魔杖（管理员）
/biyi_atlas unlock <区域id> [玩家]   解锁区域（管理员）
/biyi_atlas license <区域id> [玩家]  发放区域许可证（管理员）
```
