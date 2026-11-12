from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
from urllib.parse import urlsplit
from PIL import Image,ImageStat
import sys,json,random,hashlib,subprocess,time
P=Path(__file__).resolve().parent
sys.path.insert(0,str(P.parent/'ad-structure-20261113'))
import analyze as a

def plan():
    frozen=json.loads((P/'selection.json').read_text('utf-8'));rows=json.loads((P/'resolved.json').read_text('utf-8'))['cases']
    rules=(a.ASSETS/'known-hls-ads.tsv').read_text('utf-8');profiles=[];known_paths=set()
    for line in rules.splitlines():
        if line.startswith('sequence\t'):profiles.append([int(x.split(':')[0]) for x in line.split('\t')[1].split(',')])
        if line.startswith('segment\t'):
            u=urlsplit(line.split('\t')[1]);known_paths.add((u.netloc.lower(),u.path))
    jobs={};cases=[]
    for case in rows:
        if case['status']!='resolved':continue
        r=case['route'];ss=r['segments'];chosen={};windows=[]
        def choose(i,reason):chosen.setdefault(i,[]).append(reason)
        for t in [0,min(30,r['duration']*.15),min(60,r['duration']*.3),r['duration']*.25,r['duration']*.5,r['duration']*.75,r['duration']*.95]:
            choose(next(s['i'] for s in ss if s['start']+s['duration']>t),'distributed sample')
        for i in range(len(ss)):
            u=urlsplit(ss[i]['url'])
            if (u.netloc.lower(),u.path) in known_paths:
                choose(i,'reviewed URL candidate')
            for profile in profiles:
                if i+len(profile)<=len(ss) and all(abs(round(ss[i+j]['duration']*1000)-v)<=1 for j,v in enumerate(profile)):
                    windows.append([i,i+len(profile)])
                    for j in range(max(0,i-1),min(len(ss),i+len(profile)+1)):choose(j,'reviewed-profile candidate and plot neighbors')
        shorts=[run for run in r['runs'] if run['a']>0 and 4<=run['duration']<=40]
        rng=random.Random(frozen['seed']^int(r['id'],16))
        if shorts:
            selected=[shorts[0]]
            if len(shorts)>1:selected+=rng.sample(shorts[1:],1)
            for run in selected:choose((run['a']+run['b'])//2,'short discontinuity run (not automatically an ad)')
        for i in r['host_changes'][:2]:
            choose(i,'host transition');choose(i-1,'before host transition')
        samples=[]
        for i,reasons in sorted(chosen.items()):
            s=ss[i];key=hashlib.sha256(s['url'].encode()).hexdigest()[:20];jobs.setdefault(key,{'key':key,'url':s['url'],'referer':r['page']})
            samples.append({'index':i,'start':s['start'],'duration':s['duration'],'url':s['url'],'key':key,'reasons':reasons})
        cases.append({'title':case['title'],'episode':case['episode'],'route_id':r['id'],'page':r['page'],'manifest':r['manifest'],'duration':r['duration'],'encrypted':r['encrypted'],'extended_format':r.get('extended_format',False),'candidate_windows':windows,'samples':samples})
    result={'cases':cases,'resources':list(jobs.values()),'rules_sha256':hashlib.sha256(rules.encode()).hexdigest(),'sampling_seed':frozen['seed']}
    (P/'frame-plan.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PLAN',len(cases),'episodes',sum(len(c['samples']) for c in cases),'sample records',len(jobs),'unique resources',flush=True)

def fetch_one(item):
    out=P/(item['key']+'.ts')
    try:
        if not out.exists():
            actual,data,headers=a.get(item['url'],item['referer']);out.write_bytes(data)
        else:actual=item['url'];data=out.read_bytes();headers={}
        return {**item,'status':'downloaded','file':out.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'final_url':actual,'headers':{k:v for k,v in headers.items() if k.lower() in ['content-type','server','etag','last-modified']}}
    except Exception as error:return {**item,'status':'download_failed','error':str(error)[:260]}

def download():
    targets=json.loads((P/'frame-plan.json').read_text('utf-8'))['resources'];save=P/'resources.json'
    old=json.loads(save.read_text('utf-8'))['resources'] if save.exists() else [];done={x['key']:x for x in old if x['status']=='downloaded'}
    jobs=[x for x in targets if x['key'] not in done];results=dict(done)
    with ThreadPoolExecutor(max_workers=5) as pool:
        for future in as_completed([pool.submit(fetch_one,x) for x in jobs]):
            row=future.result();results[row['key']]=row
            if len(results)%20==0 or row['status']!='downloaded':print('FETCH',len(results),'/',len(targets),row['status'],row.get('error',''),flush=True)
            save.write_text(json.dumps({'resources':list(results.values())},ensure_ascii=False,indent=2),encoding='utf-8')
    print('DOWNLOAD DONE',len(results),'failed',sum(x['status']!='downloaded' for x in results.values()),flush=True)

def decode_one(item):
    if item['status']!='downloaded':return {**item,'frame_status':'not_downloaded'}
    try:
        src=P/item['file'];dest=src.with_suffix('.jpg')
        probe=subprocess.run(['ffprobe','-v','error','-show_entries','format=start_time,duration:stream=codec_name,codec_type,width,height,r_frame_rate,start_time','-of','json',str(src)],capture_output=True,check=True,timeout=15)
        info=json.loads(probe.stdout)
        def image(offset):
            vf='setpts=PTS-STARTPTS,'+(f'select=gte(t\\,{offset}),' if offset else '')+'scale=480:-2,format=yuvj420p'
            proc=subprocess.run(['ffmpeg','-v','error','-y','-i',str(src),'-map','0:v:0','-sn','-an','-vf',vf,'-frames:v','1',str(dest)],capture_output=True,timeout=20)
            if proc.returncode or not dest.exists():raise ValueError(proc.stderr.decode(errors='replace')[-240:] or 'no decoded frame')
        offset=0;image(0)
        brightness=sum(ImageStat.Stat(Image.open(dest).convert('RGB')).mean)/3
        duration=float(info.get('format',{}).get('duration',0))
        if brightness<8 and duration>1.3:
            original=dest.read_bytes()
            try:
                image(1);new_brightness=sum(ImageStat.Stat(Image.open(dest).convert('RGB')).mean)/3
                if new_brightness>brightness:offset=1;brightness=new_brightness
                else:dest.write_bytes(original)
            except Exception:dest.write_bytes(original)
        return {**item,'frame_status':'decoded','frame':dest.name,'offset':offset,'mean_brightness':round(brightness,2),'probe':info}
    except Exception as error:return {**item,'frame_status':'decode_failed','decode_error':str(error)[:350]}

def frames():
    resources=json.loads((P/'resources.json').read_text('utf-8'))['resources'];save=P/'frames.json';old=json.loads(save.read_text('utf-8'))['resources'] if save.exists() else []
    done={x['key']:x for x in old if x.get('frame_status')=='decoded'};results=dict(done);jobs=[x for x in resources if x['key'] not in done]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for future in as_completed([pool.submit(decode_one,x) for x in jobs]):
            row=future.result();results[row['key']]=row
            if len(results)%25==0 or row['frame_status']!='decoded':print('FRAME',len(results),'/',len(resources),row['frame_status'],row.get('decode_error',''),flush=True)
            save.write_text(json.dumps({'resources':list(results.values())},ensure_ascii=False,indent=2),encoding='utf-8')
    print('FRAMES DONE',len(results),'decoded',sum(x['frame_status']=='decoded' for x in results.values()),flush=True)
if __name__=='__main__':
    for step in sys.argv[1:]:{'plan':plan,'download':download,'frames':frames}[step]()
