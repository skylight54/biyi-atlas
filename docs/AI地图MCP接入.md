# AI地图桥（首版）

> 本文记录首版流程。当前MCP已增加整区准备、底片导出、美化候选导入等工具，当前单图上限为3MiB；使用方法和资源路径请阅读 [当前版本README](../README.md#6-ai美化展示图与mcp作者可选)。

实现：`tools/atlas_mcp.py`（Python 3，标准库，无额外依赖）和模组 `AtlasAiBridge`。
采用 MCP 2024-11-05 stdio JSON-RPC 协议；参考[传输规范](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)和[工具规范](https://modelcontextprotocol.io/specification/2024-11-05/server/tools)。没有网络监听端口。

在支持 stdio MCP 的 AI 客户端中注册以下进程（客户端配置字段因产品而异）：

```json
{
  "mcpServers": {
    "biyi-atlas": {
      "command": "python",
      "args": [
        "D:/DSH开发/biyi-atlas/tools/atlas_mcp.py",
        "--game-dir",
        "F:/避役这一块/避役科工作端/.minecraft/versions/1.20.1-Forge_47.4.8"
      ]
    }
  }
}
```

## 工作流

1. 更新模组并进入世界，打开作者地图。桥只在有作者权限、地图界面打开时每秒检查一次请求。普通玩家界面不消费请求。
2. AI调用 `capture_map`，获取request_id。约一秒后调用 `read_map(request_id)`，返回合并后的区域/道路/地点JSON、修订哈希及实际游戏帧PNG。若pending，等待作者地图打开后重试read_map。
3. AI根据截图提出样式调整，再调用 `style_road(revision,road,width,rgb,dashed)`。road是JSON里的稳定道路id；width为1..12整数，rgb为0..16777215整数。它只修改样式，保留点位、节点和支线关联。
4. 读取操作回执。每次编辑前会将作者内容备份到游戏目录 `biyi_atlas/ai_bridge/backups/`，并在游戏中提示。修订不一致时拒绝，须重新拉取。
5. **编辑后再次capture_map/read_map检查真实新帧。** 编辑回执不附图，避免误将编辑前一帧当成新效果。每次请求最多截图一次，不循环截图，不在每帧读地图。

请求60秒过期；同一时刻只允许一个待处理请求。若超时但游戏未打开，请删除 `biyi_atlas/ai_bridge/request.json` 后重新请求。响应带request_id与capturedAt，需核对防止读到上一次结果。

## 本轮验证与范围

- 4项Python检查覆盖stdio初始化/工具列表、请求排队、并发占用拒绝、旧回执过滤、编辑回执不混用旧截图、样式边界。
- Java桥已编译；仍需启动新版模组做MCP→游戏→截图→修改→新截图的实际闭环验收。尚未注册到当前AI会话，不能宣称现在已连通。
- 首版开放读取地图和道路样式编辑。地形画笔、AI自动重布路网、区域点位编辑、批量素材替换、自动构图尚未实现。MCP提供操作与验收通路，不会自动把当前地形变成概念图。
- 截图包含当前游戏窗口内容；在作者地图中请求。没有向外上传截图的后台任务，数据仅经所连接的AI客户端工具调用读取。

## 每轮预览

当前无法运行新版游戏时，从指定实例只读复制最新NBT与作者JSON到 `build/qa/`，使用 `previewTerrain` 生成 `docs/art/actual-map-v9.png` 后目视检查。它使用同一TerrainRaster/MapStroke/RoadStyle，展示真实采样和路线，但不含游戏UI、区域可见性遮罩、图标和GPU实际合成；明确属于离线预览。

2026-10-19更新：已使用codex mcp add注册全局biyi-atlas并实际抓取游戏帧。新增publish_area（area+revision），发布后必须再capture/read核对服务端回执和接收采样数。已验证region_1从0样本恢复到42095样本。
