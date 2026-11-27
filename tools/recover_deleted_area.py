"""Recover only missing area records and cleared ownership from a known prior snapshot."""
import copy, hashlib, json, os, pathlib, sys

game = pathlib.Path(sys.argv[1])
audit = pathlib.Path('recovery/20261019-delete')
old = json.loads((audit/'last-mcp-map.json').read_text(encoding='utf-8'))
expected = (audit/'current-authored.json').read_bytes()
area = 'region_1'
rows = old['Regions']['list']
ids = {area}
while True:
    expanded = ids | {r['id'] for r in rows if r.get('parent') in ids}
    if expanded == ids: break
    ids = expanded
assert len(ids) == 5, ids
files = [game/'biyi_atlas/authored/sp_避役科 - 副本 - 副本/content.json',
         game/'saves/避役科 - 副本 - 副本/data/biyi_atlas_content.json']
prepared = []
for i, path in enumerate(files):
    raw = path.read_bytes()
    assert raw == expected, f'{path}: data changed; stop and recompare'
    (audit/f'before-restore-{i}.json').write_bytes(raw)
    doc = json.loads(raw)
    existing = {r['id'] for r in doc['Regions']['list']}
    added = [copy.deepcopy(r) for r in rows if r['id'] in ids and r['id'] not in existing]
    doc['Regions']['list'].extend(added)
    doc['Removed']['regions'] = [k for k in doc['Removed']['regions'] if k not in ids]
    restored = 0
    for section in ('Places', 'Roads', 'Maps'):
        def key(r): return r.get('id') or (r.get('dimension'), r.get('layer'), r.get('name'))
        previous = {key(r):r for r in old[section]['list']}
        for row in doc[section]['list']:
            before = previous.get(key(row), {})
            if before.get('region') in ids and not row.get('region'):
                row['region'] = before['region']; restored += 1
    assert len(added) == 5 and restored == 6, (len(added), restored)
    payload = json.dumps(doc, ensure_ascii=False, indent=2).encode('utf-8')
    (audit/f'restored-{i}.json').write_bytes(payload)
    prepared.append((path, raw, payload))
    print(f'{path}: restore {len(added)} regions, {restored} ownership links; preserve other fields')
if '--apply' in sys.argv:
    for path, raw, payload in prepared:
        assert path.read_bytes() == raw, 'Concurrent content modification'
    for path, raw, payload in prepared:
        staged = path.with_name(path.name+'.recovery.tmp')
        staged.write_bytes(payload); os.replace(staged, path)
        assert path.read_bytes() == payload
    print('Recovery applied; backups preserved at '+str(audit.resolve()))
