"""Read-only NBT terrain summary; Python standard library only."""
import collections
import gzip
import io
import struct
import sys

stream = io.BytesIO(gzip.open(sys.argv[1], 'rb').read())
def number(fmt):
    return struct.unpack('>' + fmt, stream.read(struct.calcsize('>' + fmt)))[0]
def string():
    return stream.read(number('H')).decode('utf-8', errors='replace')
def value(kind):
    if kind in (1, 2, 3, 4, 5, 6):
        return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
    if kind == 7: return stream.read(number('i'))
    if kind == 8: return string()
    if kind == 9:
        item_kind, count = number('B'), number('i')
        return [value(item_kind) for _ in range(count)]
    if kind == 10:
        result = {}
        while (tag := number('B')) != 0:
            name = string()
            result[name] = value(tag)
        return result
    if kind in (11, 12):
        return [number('i' if kind == 11 else 'q') for _ in range(number('i'))]
    raise ValueError(kind)
kind = number('B')
string()
root = value(kind)
survey = root.get('terrainSurvey', {})
values = survey.get('values', [])
heights = [struct.unpack('>h', struct.pack('>H', v & 65535))[0] for v in values]
print('samples:', len(values), 'version:', survey.get('version'))
print('kinds:', dict(collections.Counter((v >> 16) & 255 for v in values)))
print('height range:', (min(heights), max(heights)) if heights else None)
print('common heights:', collections.Counter(heights).most_common(10))
grid = dict(zip(survey.get('keys', []), heights))
def key(x, z):
    packed = (x & 0xffffffff) | ((z & 0xffffffff) << 32)
    return packed if packed < (1 << 63) else packed - (1 << 64)
print('near screenshot:')
for z in range(-762, -755):
    print([grid.get(key(x, z)) for x in range(-723, -716)])
