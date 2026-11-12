import sys,pickle,collections
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from buckets import cat,dec
rows=pickle.load(open(sys.argv[1],'rb'))
for want in sys.argv[2:]:
    c=collections.Counter()
    for tn,s,w,fr in rows:
        if cat(tn,fr).startswith(want): c[dec(fr[0][0])[:90]]+=w
    print('====',want)
    for k,v in c.most_common(10): print(round(v/1e6),k)
