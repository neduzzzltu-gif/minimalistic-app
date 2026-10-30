"""Print a compact view of a uiautomator dump: package, resource-id, text, desc, bounds."""
import io; sys_stdout_fix = None
import sys, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8")
root = ET.parse(sys.argv[1]).getroot()
pk = set()
for n in root.iter('node'):
    a = n.attrib; pk.add(a.get('package'))
    rid = a.get('resource-id', '').split(':id/')[-1]
    t, d = a.get('text', ''), a.get('content-desc', '')
    sel = '*' if a.get('selected') == 'true' else ''
    if rid or t or d:
        print(f"{rid}{sel} | {t[:50]!r} | {d[:50]!r} | {a.get('class','').split('.')[-1]} {a.get('bounds')}")
print('packages:', pk)
