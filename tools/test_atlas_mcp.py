import json
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys
from atlas_mcp import call

class BridgeChecks(unittest.TestCase):
    def test_request_and_busy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            result=call(root,"capture_map",{})
            request=json.loads((root/"request.json").read_text())
            self.assertEqual(request["action"],"capture")
            self.assertIn(request["id"],result["content"][0]["text"])
            with self.assertRaises(ValueError):call(root,"capture_map",{})
    def test_stale_and_edit_response(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"response.json").write_text(json.dumps({"id":"new","ok":True,"screenshot":False}))
            (root/"map.json").write_text("{}")
            self.assertIn("pending",call(root,"read_map",{"request_id":"old"})["content"][0]["text"])
            result=call(root,"read_map",{"request_id":"new"})
            self.assertFalse(any(x["type"]=="image" for x in result["content"]))
    def test_invalid_style(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):call(Path(tmp),"style_road",{"width":0,"rgb":0,"dashed":False})
    def test_presentation_registration_manifest_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/"bridge"
            manifest=Path(tmp)/"manifest.json";png=Path(tmp)/"art.png"
            matrix=[1.006,-.002,-.003,-.005,1.078,-.004]
            document={"area":"region_2","base":"original-base","shape":"original-shape","registration":matrix}
            manifest.write_text(json.dumps(document),encoding="utf-8");png.write_bytes(b"test-image-bytes")
            call(root,"import_presentation",{"area":"region_2","revision":"rev","manifest_path":str(manifest),"png_path":str(png)})
            self.assertEqual(json.loads((root/"art-upload.json").read_text(encoding="utf-8")),document)
            self.assertEqual((root/"art-upload.png").read_bytes(),png.read_bytes())
            request=json.loads((root/"request.json").read_text(encoding="utf-8"))
            self.assertEqual(request["action"],"import_art")
            self.assertNotIn("png_path",request)
    def test_stdio_handshake(self):
        with tempfile.TemporaryDirectory() as tmp:
            requests='\n'.join(json.dumps(x) for x in [{"jsonrpc":"2.0","id":1,"method":"initialize"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"tools/list"}])+'\n'
            process=subprocess.run([sys.executable,str(Path(__file__).with_name("atlas_mcp.py")),"--game-dir",tmp],input=requests,text=True,capture_output=True,check=True)
            responses=[json.loads(x) for x in process.stdout.splitlines()]
            self.assertEqual(len(responses),2)
            self.assertEqual(len(responses[1]["result"]["tools"]),8)
    def test_unicode_stdio_arguments(self):
        with tempfile.TemporaryDirectory() as tmp:
            request={"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"publish_area","arguments":{"area":"落日_测试","revision":"abc"}}}
            subprocess.run([sys.executable,str(Path(__file__).with_name("atlas_mcp.py")),"--game-dir",tmp],input=json.dumps(request,ensure_ascii=False)+'\n',text=True,encoding="utf-8",capture_output=True,check=True)
            queued=json.loads((Path(tmp)/"biyi_atlas/ai_bridge/request.json").read_text(encoding="utf-8"))
            self.assertEqual(queued["area"],"落日_测试")

if __name__=="__main__":unittest.main()
