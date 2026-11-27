"""Exercise real stdio MCP tools and save image/text results without dumping base64."""
import argparse,base64,json,subprocess,sys,time
from pathlib import Path

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--game-dir',required=True);ap.add_argument('--out',required=True)
    ap.add_argument('--publish');ap.add_argument('--revision');ap.add_argument('--tool');ap.add_argument('--args-file');a=ap.parse_args()
    out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    p=subprocess.Popen([sys.executable,str(Path(__file__).with_name('atlas_mcp.py')),'--game-dir',a.game_dir],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,encoding='utf-8')
    seq=0
    def rpc(method,params):
        nonlocal seq
        seq+=1;p.stdin.write(json.dumps(dict(jsonrpc='2.0',id=seq,method=method,params=params))+'\n');p.stdin.flush()
        response=json.loads(p.stdout.readline())
        if 'error' in response:raise RuntimeError(response['error'])
        return response['result']
    try:
        rpc('initialize',{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'atlas-qa','version':'1'}})
        p.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n');p.stdin.flush()
        tool=a.tool or ('publish_area' if a.publish else 'capture_map')
        arguments=json.loads(Path(a.args_file).read_text(encoding='utf-8')) if a.args_file else ({'area':a.publish,'revision':a.revision} if a.publish else {})
        queued=rpc('tools/call',{'name':tool,'arguments':arguments})
        if queued.get('isError'):raise RuntimeError(queued)
        request_id=json.loads(queued['content'][0]['text'])['request_id']
        for attempt in range(90):
            result=rpc('tools/call',{'name':'read_map','arguments':{'request_id':request_id}})
            if result['content'][0]['text'].startswith('pending'):
                time.sleep(1);continue
            metadata=json.loads(result['content'][0]['text'])
            (out/'response.json').write_text(json.dumps(metadata,ensure_ascii=False),encoding='utf-8')
            if result.get('isError'):raise RuntimeError(metadata)
            text_index=0
            for block in result['content'][1:]:
                if block['type']=='image':(out/('base.png' if metadata.get('exportDirectory') else 'map.png')).write_bytes(base64.b64decode(block['data']))
                elif block['type']=='text':
                    (out/('map.json' if text_index==0 else 'manifest.json')).write_text(block['text'],encoding='utf-8');text_index+=1
            print(json.dumps(metadata));print('Saved actual MCP map capture to '+str(out));return
        raise TimeoutError('Game did not consume capture request; keep author map open')
    finally:
        p.stdin.close();p.wait(timeout=5)

if __name__=='__main__':main()
