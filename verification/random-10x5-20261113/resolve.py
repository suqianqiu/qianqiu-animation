from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
import sys,json,time,hashlib
P=Path(__file__).resolve().parent
sys.path.insert(0,str(P.parent/'ad-structure-20261113'))
import analyze as a
a.OUT=P

def work(task):
    attempts=[]
    for option in task['options']:
        try:
            route=a.playlist(option)
            if not route['segments']:raise ValueError('empty playlist')
            if any(tag in (P/(route['id']+'.m3u8')).read_text('utf-8') for tag in ['#EXT-X-BYTERANGE','#EXT-X-MAP']):
                route['extended_format']=True
            attempts.append({'page':option['page'],'status':'resolved'})
            return {**task,'status':'resolved','route':route,'attempts':attempts,'fetched_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
        except Exception as error:
            attempts.append({'page':option['page'],'status':'failed','error':str(error)[:260]})
    return {**task,'status':'unavailable','attempts':attempts}

def main():
    selection=json.loads((P/'selection.json').read_text('utf-8'));saved=P/'resolved.json'
    rows=json.loads(saved.read_text('utf-8'))['cases'] if saved.exists() else []
    done={(r['title'],r['episode']) for r in rows};jobs=[t for t in selection['tasks'] if (t['title'],t['episode']) not in done]
    with ThreadPoolExecutor(max_workers=3) as pool:
        for future in as_completed([pool.submit(work,t) for t in jobs]):
            result=future.result();rows.append(result)
            saved.write_text(json.dumps({'cases':rows},ensure_ascii=False,indent=2),encoding='utf-8')
            r=result.get('route',{});print(len(rows),result['title'],result['episode'],result['status'],round(r.get('duration',0),3),'encrypted',r.get('encrypted'),flush=True)
    print('DONE',len(rows),'resolved',sum(r['status']=='resolved' for r in rows),flush=True)
if __name__=='__main__':main()
