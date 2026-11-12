"""Reproducible random sample, selected before inspecting any video frames."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote,urljoin
from lxml import html
import sys,json,re,random,secrets,unicodedata,time

P=Path(__file__).resolve().parent
sys.path.insert(0,str(P.parent/'ad-structure-20261113'))
import analyze as a

def norm(s):return ''.join(c for c in unicodedata.normalize('NFKC',s).lower() if c.isalnum())
def discover(entry,source='MXdm'):
    rule=json.loads((a.ASSETS/(source+'.json')).read_text('utf-8-sig'))
    accepted={norm(x) for x in entry['aliases']};matches=[]
    for query in entry['aliases'][:2]:
        u,b,_=a.get(rule['searchURL'].replace('@keyword',quote(query)),limit=4000000)
        d=html.fromstring(a.decode(b))
        for node in d.xpath(rule['searchList']):
            names=a.rel(node,rule['searchName']);links=a.rel(node,rule['searchResult'])
            if names and links and links[0].get('href'):
                name=''.join(names[0].itertext()).strip()
                if norm(name) in accepted:matches.append((name,urljoin(u,links[0].get('href'))))
        if matches:break
    if not matches:return {'title':entry['title'],'status':'no exact series match'}
    selected_title,detail=matches[0];detail,b,_=a.get(detail);doc=html.fromstring(a.decode(b));roads=[]
    for i,node in enumerate(doc.xpath(rule['chapterRoads']),1):
        episodes={}
        for link in a.rel(node,rule['chapterResult']):
            label=link.text_content().strip();m=re.fullmatch(r'(?:第\s*)?0*(\d{1,4})(?:\s*[集话話])?',label)
            if m and link.get('href'):episodes[int(m.group(1))]=urljoin(detail,link.get('href'))
        if episodes:roads.append({'road':i,'episodes':episodes})
    available=sorted({ep for r in roads for ep in r['episodes']})
    return {'title':entry['title'],'selected_title':selected_title,'source':source,'detail':detail,'roads':roads,'available':available,'status':'eligible' if len(available)>=5 else 'fewer than five numbered episodes'}

def run():
    if (P/'selection.json').exists():print('Selection already frozen; no redraw.');return
    planfile=P/'sampling-plan.json'
    if planfile.exists():plan=json.loads(planfile.read_text('utf-8'))
    else:
        text=(a.ASSETS.parent/'src/com/qianq/kandongman/AnimeCatalog.java').read_text('utf-8').split('private static final String[] DATA={',1)[1].split('};',1)[0]
        entries=[];seen=set()
        for line in re.findall(r'"([^"\r\n]+\|[^"\r\n]+)"',text):
            fields=line.split('|');title={'美食猎人':'美食的俘虏','百变小樱魔术卡':'魔卡少女樱'}.get(fields[0],fields[0])
            if title in seen:continue
            seen.add(title);aliases=list(dict.fromkeys([title]+[x for x in fields if re.search(r'[\u4e00-\u9fff]',x)]));entries.append({'title':title,'aliases':aliases})
        seed=secrets.randbits(64);random.Random(seed).shuffle(entries)
        plan={'seed':seed,'created_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'population':len(entries),'method':'shuffle catalog; retain first ten exact-match series with at least five numbered episodes; random five episodes and random primary road, all before frame inspection','candidates':entries}
        planfile.write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
    progressfile=P/'discovery.json';progress=json.loads(progressfile.read_text('utf-8')) if progressfile.exists() else {'attempts':[],'eligible':[]}
    done={x['title'] for x in progress['attempts']};jobs=[e for e in plan['candidates'] if e['title'] not in done]
    for start in range(0,len(jobs),6):
        batch=jobs[start:start+6]
        def safe(e):
            try:return discover(e)
            except Exception as error:return {'title':e['title'],'status':'lookup failed','error':str(error)[:240]}
        with ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(safe,batch))
        for result in results:
            progress['attempts'].append(result)
            if result['status']=='eligible' and len(progress['eligible'])<10:progress['eligible'].append(result)
            print('discovery',result['title'],result['status'],'eligible',len(progress['eligible']),flush=True)
        progressfile.write_text(json.dumps(progress,ensure_ascii=False,indent=2),encoding='utf-8')
        if len(progress['eligible'])>=10:break
    assert len(progress['eligible'])==10,'Could not find ten eligible catalog series'
    rng=random.Random(plan['seed']^0xE9150DE5);series=[];tasks=[]
    for item in progress['eligible']:
        episodes=sorted(rng.sample(item['available'],5));series.append({k:v for k,v in item.items() if k!='roads'}|{'sampled_episodes':episodes})
        for ep in episodes:
            options=[{'source':'MXdm','title':item['title'],'selected_title':item['selected_title'],'episode':ep,'road':r['road'],'page':r['episodes'].get(str(ep),r['episodes'].get(ep))} for r in item['roads'] if str(ep) in r['episodes'] or ep in r['episodes']]
            rng.shuffle(options);tasks.append({'title':item['title'],'episode':ep,'options':options})
    frozen={'seed':plan['seed'],'catalog_population':plan['population'],'series':series,'tasks':tasks,'frozen_before_media_fetch':True}
    (P/'selection.json').write_text(json.dumps(frozen,ensure_ascii=False,indent=2),encoding='utf-8')
    for s in series:print('SELECTED',s['title'],s['sampled_episodes'],flush=True)
if __name__=='__main__':run()
