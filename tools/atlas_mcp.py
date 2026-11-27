"""Local stdio MCP server, Python 3 standard library. No network listener."""
import argparse
import base64
import json
import os
from pathlib import Path
import sys
import time
import uuid
import threading
import shutil

TOOLS = [
    {"name":"show_map","description":"For an authorized author, switch map to author/player preview and select a panel to inspect via a following capture. Does not change map data.","inputSchema":{"type":"object","properties":{"mode":{"type":"string","enum":["author","player"]},"panel":{"type":"string","enum":["none","details","mcp","regions","view"]}},"required":["mode","panel"],"additionalProperties":False}},
    {"name":"prepare_area","description":"One-click server loading/surveying and automatic publish of a whole existing map area; can load/generate chunks. Monitor capture preparation/complete.","inputSchema":{"type":"object","properties":{"area":{"type":"string"},"revision":{"type":"string"}},"required":["area","revision"],"additionalProperties":False}},
    {"name":"export_art_bundle","description":"Export a fully prepared active area's real terrain PNG and world metadata for AI beautification. Requires complete=true. Original coordinate registration must be preserved.","inputSchema":{"type":"object","properties":{"area":{"type":"string"},"revision":{"type":"string"}},"required":["area","revision"],"additionalProperties":False}},
    {"name":"import_presentation","description":"Upload optional AI art PNG with unchanged exported manifest. Server validates original base/shape and author permissions; routes/markers remain runtime overlays. PNG<=3MiB, axes<=2048.","inputSchema":{"type":"object","properties":{"area":{"type":"string"},"revision":{"type":"string"},"png_path":{"type":"string"},"manifest_path":{"type":"string"}},"required":["area","revision","png_path","manifest_path"],"additionalProperties":False}},

    {"name": "publish_area", "description": "Request publishing surveyed terrain for an existing map area from the open author map. revision must match latest capture. This queues network work; verify server receipt/log and next capture terrainStatus/receivedSamples, not just ok.", "inputSchema": {"type":"object","properties":{"revision":{"type":"string"},"area":{"type":"string"}},"required":["revision","area"],"additionalProperties":False}},
    {"name": "capture_map", "description": "Request fresh map JSON and actual game screenshot. Open the author map first; poll read_map with returned request_id.", "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "read_map", "description": "Read completed capture/edit response, merged map and actual PNG screenshot. Check id and capturedAt; never treat stale frames as fresh.", "inputSchema": {"type": "object", "properties": {"request_id": {"type": "string"}}, "required": ["request_id"], "additionalProperties": False}},
    {"name": "style_road", "description": "Update one road style in the open author map with backup and optimistic revision check. Geometry and nodes stay intact. Request capture_map afterwards to inspect the rendered result.", "inputSchema": {"type": "object", "properties": {"revision": {"type": "string"}, "road": {"type": "string"}, "width": {"type": "integer", "minimum": 1, "maximum": 12}, "rgb": {"type": "integer", "minimum": 0, "maximum": 16777215}, "dashed": {"type": "boolean"}}, "required": ["revision", "road", "width", "rgb", "dashed"], "additionalProperties": False}},
]

def call(root, name, args):
    root.mkdir(parents=True, exist_ok=True)
    if name == "read_map":
        response = root / "response.json"
        if not response.exists():
            return {"content": [{"type": "text", "text": "pending: open author map and wait for game tick"}]}
        data = json.loads(response.read_text(encoding="utf-8"))
        if data.get("id") != args["request_id"]:
            return {"content": [{"type": "text", "text": "pending: response belongs to a different request"}]}
        blocks = [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}]
        if data.get("ok"):
            blocks.append({"type": "text", "text": (root / "map.json").read_text(encoding="utf-8")})
            if data.get("screenshot"):
                image = (root / "map.png").read_bytes()
                if len(image) <= 10 * 1024 * 1024:
                    blocks.append({"type": "image", "mimeType": "image/png", "data": base64.b64encode(image).decode("ascii")})
            if data.get("exportDirectory"):
                blocks.append({"type":"text","text":(root/"art-export/manifest.json").read_text(encoding="utf-8")})
                blocks.append({"type":"image","mimeType":"image/png","data":base64.b64encode((root/"art-export/base.png").read_bytes()).decode("ascii")})
        return {"content": blocks, "isError": not data.get("ok", False)}
    if name not in ("capture_map", "style_road", "publish_area", "prepare_area", "export_art_bundle", "import_presentation", "show_map"):
        raise ValueError("Unknown tool")
    if (root / "request.json").exists():
        raise ValueError("A request is pending. Open author map to consume it before sending another.")
    if name == "style_road":
        if not 1 <= args["width"] <= 12 or not 0 <= args["rgb"] <= 0xffffff or type(args["dashed"]) is not bool:
            raise ValueError("Invalid style")
    if name == "import_presentation":
        manifest=json.loads(Path(args["manifest_path"]).read_text(encoding="utf-8"))
        if manifest["area"] != args["area"]:raise ValueError("Manifest area mismatch")
        source=Path(args["png_path"])
        if source.stat().st_size>3*1024*1024:raise ValueError("PNG exceeds 3MiB")
        shutil.copyfile(source,root/"art-upload.png")
        (root/"art-upload.json").write_text(json.dumps(manifest),encoding="utf-8")
        args={k:v for k,v in args.items() if k not in ("png_path","manifest_path")}
    request_id = str(uuid.uuid4())
    request = dict(args, id=request_id, created=int(time.time()*1000), action={"capture_map":"capture","style_road":"road_style","publish_area":"publish_area","prepare_area":"prepare_area","export_art_bundle":"export_art","import_presentation":"import_art","show_map":"show_view"}[name])
    tmp = root / "request.tmp"
    tmp.write_text(json.dumps(request, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, root / "request.json")
    return {"content": [{"type": "text", "text": json.dumps({"request_id": request_id, "status": "queued"})}]}

def main():
    # Windows默认控制台编码可能不是UTF-8；MCP stdio必须显式使用UTF-8。
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--game-dir", required=True)
    root = Path(parser.parse_args().game_dir).resolve() / "biyi_atlas" / "ai_bridge"
    root.mkdir(parents=True,exist_ok=True)
    def heartbeat():
        while True:
            try:(root/"heartbeat.json").write_text(json.dumps({"client":"biyi-atlas MCP","at":int(time.time()*1000)}),encoding="utf-8")
            except OSError:pass
            time.sleep(3)
    threading.Thread(target=heartbeat,daemon=True).start()
    for line in sys.stdin:
        req = {}
        try:
            req = json.loads(line)
            method = req.get("method")
            if "id" not in req:
                continue
            if method == "initialize":
                result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "biyi-atlas", "version": "0.1.0"}}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": TOOLS}
            elif method == "tools/call":
                try:
                    result = call(root, req["params"]["name"], req["params"].get("arguments", {}))
                except Exception as error:
                    result = {"content": [{"type": "text", "text": str(error)}], "isError": True}
            else:
                print(json.dumps({"jsonrpc": "2.0", "id": req["id"], "error": {"code": -32601, "message": "Method not found"}}), flush=True)
                continue
            print(json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": result}, ensure_ascii=True), flush=True)
        except Exception as error:
            print(json.dumps({"jsonrpc": "2.0", "id": req.get("id"), "error": {"code": -32603, "message": str(error)}}), flush=True)

if __name__ == "__main__":
    main()
