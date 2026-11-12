from pathlib import Path
from urllib.parse import urljoin
from concurrent.futures import ThreadPoolExecutor
import json,csv,subprocess,html
P=Path(__file__).resolve().parent
load=lambda name:json.loads((P/name).read_text('utf-8'))
notes=load('review-notes.json');notes['followup_zoom']=[]
notes['notes'].append('R249/R533 enlarged: ordinary ending credits. R569/R570 recovered and visually reviewed: normal story frames.')
(P/'review-notes.json').write_text(json.dumps(notes,ensure_ascii=False,indent=2),encoding='utf-8')
resources={r['url']:r for r in load('frames.json')['resources']}
review={key:r for r in load('review-index.json') for key in r['resources']}
known=set()
for line in (P.parents[1]/'assets/known-hls-ads.tsv').read_text('utf-8').splitlines():
    if line.startswith('segment\t'):known.add(line.split('\t')[2])
    if line.startswith('sequence\t'):known.update(x.split(':')[1] for x in line.split('\t')[1].split(','))
results={s.split('\t')[0]:s.split('\t') for s in (P/'filtered/results.tsv').read_text('utf-8').splitlines()}
routes={r['route']['id']:r['route'] for r in load('resolved.json')['cases'] if r['status']=='resolved'}
cases=[];seams=[]
for case in load('frame-plan.json')['cases']:
    rid=case['route_id'];route=routes[rid];raw=route['segments'];result=results[rid]
    assert result[1]=='FILTERED' and result[6]=='0',result
    kept=[urljoin(route['manifest'],s) for s in (P/'filtered'/f'{rid}.m3u8').read_text('utf-8').splitlines() if s and not s.startswith('#')]
    cursor=iter(kept);next_uri=next(cursor,None);removed=[]
    for s in raw:
        if s['url']==next_uri:next_uri=next(cursor,None)
        else:removed.append(s)
    assert next_uri is None and len(kept)+len(removed)==len(raw)
    assert all(resources[s['url']]['sha256'] in known for s in removed)
    cuts=json.loads(result[4]);assert sum(round(s['duration']*1000) for s in removed)==sum(b-a for a,b in zip(cuts[::2],cuts[1::2]))
    removed_i={s['i'] for s in removed};groups=[]
    for s in removed:
        if not groups or s['i']!=groups[-1][-1]+1:groups.append([])
        groups[-1].append(s['i'])
    for k,g in enumerate(groups):
        before=g[0]-1;after=g[-1]+1
        if before<0 or after>=len(raw):continue
        assert kept[kept.index(raw[before]['url'])+1]==raw[after]['url']
        if not all(raw[i]['url'] in resources for i in [before,after]):continue
        path=P/f'seam-{rid}-{k}.m3u8';text='#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:60\n#EXT-X-MEDIA-SEQUENCE:0\n'
        for i in [before,after]:text+=f"#EXTINF:{raw[i]['duration']},\n{resources[raw[i]['url']]['file']}\n#EXT-X-DISCONTINUITY\n"
        path.write_text(text+'#EXT-X-ENDLIST\n',encoding='utf-8');seams.append({'id':rid,'file':path.name,'before':before,'after':after})
    samples=[]
    for s in case['samples']:
        r=resources[s['url']];v=review[r['key']];n=int(v['review_id'][1:])
        label='reviewed_independent_ad' if r['sha256'] in known else 'promotional_text' if n in notes['promotional_text'] else 'official_information_or_credits' if n in notes['official_info_or_promo'] else 'uncertain_dark_frame' if n in notes['uncertain'] or v['dark'] else 'no_ad_observed_in_sample'
        samples.append({'time_seconds':round(s['start']+r.get('offset',0),3),'index':s['index'],'frame':r['frame'],'review_id':v['review_id'],'classification':label,'removed':s['index'] in removed_i})
    cases.append({'id':rid,'title':case['title'],'episode':case['episode'],'status':'passed_known_ad_and_order_checks','removed_segments':len(removed),'removed_breaks':len(groups),'cuts_ms':cuts,'remaining_promo_observed':any(s['classification']=='promotional_text' and not s['removed'] for s in samples),'samples':samples})
def decode(job):
    out=P/(job['file']+'.framemd5')
    p=subprocess.run(['ffmpeg','-v','error','-y','-protocol_whitelist','file,crypto,data','-allowed_extensions','ALL','-i',str(P/job['file']),'-map','0:v:0','-an','-vf','fps=1,scale=320:-2','-f','framemd5',str(out)],capture_output=True,timeout=45)
    frames=[s for s in out.read_text().splitlines() if s and not s.startswith('#')] if out.exists() else []
    return {**job,'passed':p.returncode==0 and len(frames)>=2,'decoded_frames':len(frames),'error':p.stderr.decode(errors='replace')[:300] if p.returncode else ''}
with ThreadPoolExecutor(max_workers=3) as pool:decoded=list(pool.map(decode,seams))
assert all(s['passed'] for s in decoded),[s for s in decoded if not s['passed']]
titles=[]
for title in dict.fromkeys(c['title'] for c in cases):
    rows=[c for c in cases if c['title']==title]
    titles.append({'title':title,'episodes':sorted(c['episode'] for c in rows),'removed_segments':sum(c['removed_segments'] for c in rows),'removed_breaks':sum(c['removed_breaks'] for c in rows),'remaining_promo_episodes':sorted(c['episode'] for c in rows if c['remaining_promo_observed'])})
summary={'date':'2026-11-13','version':'1.6.8','seed':load('effective-selection.json')['seed'],'titles':titles,'episodes':len(cases),'unique_decoded_segments':len(resources),'unique_images':len(load('review-index.json')),'sample_records':sum(len(c['samples']) for c in cases),'removed_segments':sum(c['removed_segments'] for c in cases),'removed_breaks':sum(c['removed_breaks'] for c in cases),'decoded_plot_seams':len(decoded),'remaining_promo_episodes':sum(c['remaining_promo_observed'] for c in cases),'all_removed_bytes_previously_reviewed':True,'retained_uri_order_unchanged':True,'missing_frames':0,'unavailable_original_draw':{'title':'龙珠改','episodes':[7,15,69,82,97],'reason':'Primary HLS unavailable; alternate MP4 returned 401. Replaced by next eligible randomized series 凡人修仙传 before reviewing its media.'},'limitations':['Sampled frames, not exhaustive episode review.','MXdm title pages and multiple upstream CDNs; not 50 independent providers.','Offline replay with complete downloaded SHA digests; not a 50-episode real-TV deadline benchmark.','Burned-in gambling/promotional text remains. Official production logos, plot signage and end-card QR information are not automatically classified as malicious inserts.'],'tested_build':load('tested-build.json')}
(P/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
(P/'evaluation.json').write_text(json.dumps({'summary':summary,'cases':cases,'seams':decoded},ensure_ascii=False,indent=2),encoding='utf-8')
with (P/'sample-table.csv').open('w',encoding='utf-8-sig',newline='') as f:
    w=csv.writer(f);w.writerow(['title','episode','time_seconds','index','frame','review_id','classification','removed'])
    for c in cases:
        for s in c['samples']:w.writerow([c['title'],c['episode'],*s.values()])
esc=lambda x:html.escape(str(x))
body='<meta charset="utf-8"><title>随机10部×5集抽帧复核</title><style>body{font:16px sans-serif;max-width:1200px;margin:32px auto;background:#151923;color:#eef}table{border-collapse:collapse}td,th{padding:10px;border:1px solid #556}img{width:240px}details{margin:20px 0}figure{display:inline-block;margin:8px;width:250px;vertical-align:top}</style><h1>千秋动画1.6.8：随机10部×5集</h1>'
body+='<p>50集，796个分片成功抽帧；独立已知广告过滤与保留顺序检查通过。画面内推广文字仍有残留，不能称全部广告已清除。抽帧不等于逐帧或全片播放。</p><pre>'+esc(json.dumps({k:v for k,v in summary.items() if k not in ['titles','tested_build']},ensure_ascii=False,indent=2))+'</pre><table><tr><th>作品</th><th>集数</th><th>移除广告段/分片</th><th>抽到残留推广文字的集</th></tr>'
for t in titles:body+='<tr>'+''.join('<td>'+esc(v)+'</td>' for v in [t['title'],t['episodes'],f"{t['removed_breaks']} / {t['removed_segments']}",t['remaining_promo_episodes']])+'</tr>'
body+='</table>'
for c in cases:
    body+=f"<details><summary>{esc(c['title'])} {c['episode']}集：{len(c['samples'])}个抽样点</summary>"
    for s in c['samples']:body+=f"<figure><img loading='lazy' src='{s['frame']}'><figcaption>{s['time_seconds']}s {s['review_id']}<br>{s['classification']}<br>过滤后：{'已移除' if s['removed'] else '保留'}</figcaption></figure>"
    body+='</details>'
(P/'report.html').write_text(body,encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)
