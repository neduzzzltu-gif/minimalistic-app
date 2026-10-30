"""Tap the centre of the first node in a dump matching a resource-id/text/desc substring.
Usage: python tap.py dump.xml <substring>"""
import sys, re, subprocess, xml.etree.ElementTree as ET
want = sys.argv[2]
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    a = n.attrib
    if want in (a.get('resource-id','').split(':id/')[-1], a.get('text',''), a.get('content-desc','')):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', a['bounds']))
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)])
        print('tapped', want, a['bounds']); break
else: print('NOT FOUND', want)
