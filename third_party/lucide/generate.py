"""Rebuild pinned Lucide Android resources offline; --check verifies reproducibility.

Run from any directory: python3 third_party/lucide/generate.py [--check]
Source SVGs and their SHA-256 values are fixed by manifest.json.
"""
from pathlib import Path
import hashlib
import html
import json
import sys
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
MANIFEST = json.loads((ROOT / 'manifest.json').read_text())
VERSION = MANIFEST['version']
COMMIT = MANIFEST['commit']

def num(value):return format(float(value),'.12g')

def path_data(e):
    tag=e.tag.split('}')[-1]; a=e.attrib
    if tag=='path': return a['d']
    if tag=='line': return f'M{a["x1"]},{a["y1"]} L{a["x2"]},{a["y2"]}'
    if tag in ('polyline','polygon'):
        pts=re.findall(r'-?\d*\.?\d+(?:[eE][-+]?\d+)?',a['points'])
        assert len(pts)%2==0
        return 'M'+' L'.join(','.join(pts[i:i+2]) for i in range(0,len(pts),2))+(' Z' if tag=='polygon' else '')
    if tag in ('circle','ellipse'):
        x=float(a['cx']);y=float(a['cy']);rx=float(a.get('r',a.get('rx')));ry=float(a.get('r',a.get('ry')))
        return f'M{num(x-rx)},{num(y)} A{num(rx)},{num(ry)} 0 1,0 {num(x+rx)},{num(y)} A{num(rx)},{num(ry)} 0 1,0 {num(x-rx)},{num(y)} Z'
    if tag=='rect':
        x=float(a.get('x',0));y=float(a.get('y',0));w=float(a['width']);h=float(a['height'])
        rx=min(float(a.get('rx',a.get('ry',0))),w/2);ry=min(float(a.get('ry',a.get('rx',0))),h/2)
        if not rx or not ry:return f'M{x},{y} h{w} v{h} h{-w} Z'
        return f'M{x+rx},{y} H{x+w-rx} A{rx},{ry} 0 0,1 {x+w},{y+ry} V{y+h-ry} A{rx},{ry} 0 0,1 {x+w-rx},{y+h} H{x+rx} A{rx},{ry} 0 0,1 {x},{y+h-ry} V{y+ry} A{rx},{ry} 0 0,1 {x+rx},{y} Z'
    raise ValueError('Unsupported SVG element: '+tag)

def convert(svg):
    root=ET.fromstring(svg)
    assert root.attrib['viewBox']=='0 0 24 24'
    assert root.attrib.get('fill')=='none'
    assert root.attrib.get('stroke-width')=='2'
    assert root.attrib.get('stroke-linecap')=='round' and root.attrib.get('stroke-linejoin')=='round'
    paths=[]
    for e in root:
        assert not any(k in e.attrib for k in ('transform','style','stroke','stroke-width')), e.attrib
        fill=e.attrib.get('fill','none')
        assert fill in ('none','currentColor')
        paths.append((path_data(e),'#FF000000' if fill=='currentColor' else '#00000000'))
    xml='<?xml version="1.0" encoding="utf-8"?>\n<!-- Lucide '+VERSION+'; '+COMMIT+'; see LICENSE. -->\n'
    xml+='<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
    for p,fill in paths:xml+='  <path android:pathData="'+html.escape(p,quote=True)+'" android:fillColor="'+fill+'" android:strokeColor="#FF000000" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
    xml+='</vector>\n'
    return xml


def main():
    check = '--check' in sys.argv
    for icon in MANIFEST['icons']:
        source = ROOT / icon['svg']
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != icon['svg_sha256']:
            raise ValueError('Source hash differs: ' + str(source))
        output = convert(data.decode('utf-8')).encode('utf-8')
        if hashlib.sha256(output).hexdigest() != icon['android_sha256']:
            raise ValueError('Generated hash differs: ' + icon['name'])
        target = REPO / icon['android']
        if check:
            if target.read_bytes() != output:
                raise ValueError('Generated resource differs: ' + str(target))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(output)
    license_target = REPO / 'app/src/main/assets/licenses/lucide-LICENSE.txt'
    license_source = (ROOT / 'LICENSE').read_bytes()
    if check:
        if license_target.read_bytes() != license_source:
            raise ValueError('Packaged Lucide license differs')
    else:
        license_target.parent.mkdir(parents=True, exist_ok=True)
        license_target.write_bytes(license_source)
    print(('Verified' if check else 'Generated') + f" {MANIFEST['count']} Lucide {VERSION} vector resources and packaged license")

if __name__ == '__main__':
    main()
