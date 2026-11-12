import sys, pickle
import xml.etree.ElementTree as ET
f=sys.argv[1]
root=ET.parse(f).getroot()
ids={}
for e in root.iter():
    if 'id' in e.attrib: ids[e.attrib['id']]=e
def res(e): return ids[e.attrib['ref']] if 'ref' in e.attrib else e
rows=[]
for row in root.iter('row'):
    d={ch.tag:res(ch) for ch in row}
    tname=d['thread'].attrib.get('fmt','?')
    frames=[]
    for fr in (d['tagged-backtrace'].iter('frame') if 'tagged-backtrace' in d else []):
        fr=res(fr); b=fr.find('binary')
        if b is not None: b=res(b)
        frames.append((fr.attrib.get('name','?'), b.attrib.get('name','?') if b is not None else '?'))
    rows.append((tname,d['thread-state'].attrib.get('fmt','?'),int(d['weight'].text),frames))
pickle.dump(rows,open(f+'.pkl','wb')); print(len(rows))
