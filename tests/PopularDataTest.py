import json,re
from pathlib import Path
p=Path(__file__).resolve().parents[1]
snapshot=json.loads((p/'assets/douban-popular.json').read_text('utf-8'))
subjects={}
for file in ('japan_tv.json','animation_tv.json','movies.json'):
    data=json.loads((p/'verification/douban'/file).read_text('utf-8'))
    for row in data if isinstance(data,list) else data['subjects']:subjects[row['url']]=row
titles=set(re.findall(r'^\s*"([^"|]+)\|', (p/'src/com/qianq/kandongman/AnimeCatalog.java').read_text('utf-8'),re.M))
seen=set();previous=10.1
for row in snapshot['items']:
    assert row['catalogTitle'] in titles and row['catalogTitle'] not in seen
    seen.add(row['catalogTitle']);assert 8<=row['rating']<=10 and row['rating']<=previous;previous=row['rating']
    original=subjects[row['url']]
    assert row['title']==original['title']
    assert row['rating']==float(original.get('rate') or original['rating'][0])
assert len(seen)>=40 and re.fullmatch(r'\d{4}-\d{2}-\d{2}',snapshot['date'])
print('PASS:',len(seen),'unique catalogue mappings, exact official subject titles/ratings, descending order, dated provenance')
