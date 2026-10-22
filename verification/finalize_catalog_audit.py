"""Rescue naming variants, then publish the completed HTTP audit to app assets."""
import collections, concurrent.futures, datetime, json, time
from pathlib import Path
import audit_catalog as audit

# Short source search terms with an explicit title match, to avoid unrelated hits.
ALIASES={
    '钢之炼金术师兄弟会':('钢之炼金术师FA','钢之炼金术师FA'),
    '86不存在的战区':('86','86'),
    '薇薇萤石眼之歌':('薇薇','薇薇'),
    'Re从零开始的异世界生活':('从零开始','异世界生活'),
    '恶役千金等级99':('恶役千金','99'),
    'Fate命运之夜':('命运之夜','命运之夜'),
    'Fate Zero':('Fate/Zero','zero'),
    'Fate冠位指定绝对魔兽战线巴比伦尼亚':('绝对魔兽战线','魔兽战线'),
    '我的心里危险的东西':('我心里危险的东西','危险的东西'),
    '在下坂本有何贵干':('坂本','在下坂本'),
    '干物妹小埋':('干物妹','小埋'),
    'BanG Dream Its MyGO':('MyGO','mygo'),
    'BanG Dream Ave Mujica':('Ave Mujica','mujica'),
    'BanG Dream':('BanG','bang'),
    'Love Live':('LoveLive','love'),
    '偶像大师':('偶像大师','偶像大师'),
    '少女乐队的呐喊':('Girls Band Cry','band cry'),
    '我们仍未知道那天所看见的花的名字':('未闻花名','未闻花名'),
    '关于我在无意间被隔壁的天使变成废柴这件事':('邻家天使','天使'),
    'Clannad':('CLANNAD','clannad'),
    'Angel Beats':('Angel','angel'),
    'Charlotte':('夏洛特','夏洛特'),
    'AIR':('青空','青空'),
    'Kanon':('雪之少女','雪之少女'),
    'NANA':('娜娜','娜娜'),
    '新世纪福音战士':('新世纪福音战士','福音战士'),
    '精灵宝可梦地平线':('地平线','宝可梦'),
    '白蛇缘起':('白蛇','缘起'),
    '白蛇浮生':('白蛇','浮生'),
    '白蛇2青蛇劫起':('青蛇劫起','青蛇劫起'),
    '新神榜哪吒重生':('哪吒重生','哪吒重生'),
}

def main():
    out=audit.OUT
    progress=json.loads((out/'progress.json').read_text('utf-8'))
    assert progress['checked']==progress['total']==431,'Full catalogue audit required'
    assert progress['tasks_completed']==progress['tasks_total'],'Media backfill must finish first'
    rows=json.loads((out/'results.json').read_text('utf-8'))
    audit.events.extend(json.loads((out/'requests.json').read_text('utf-8')))
    rules={key:json.loads((audit.ROOT/'assets'/f'{key}.json').read_text('utf-8')) for key in ('MXdm','akianime','xfdmneo')}
    def rescue(row):
        term,match=ALIASES[row['title']]
        checks=[]
        for old in row['sources']:
            check=audit.audit_source({'title':term,'aliases':[],'match':match},old['source'],rules[old['source']])
            check['original_searches']=old['searches'];checks.append(check)
        states=[check['state'] for check in checks]
        state='page_ok' if 'page_ok' in states else ('no_results' if all(s=='no_results' for s in states) else 'unknown')
        return {'title':row['title'],'state':state,'sources':checks,'alias_recheck':term}
    todo=[row for row in rows if row['state']=='no_results' and row['title'] in ALIASES]
    rescued=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for row in pool.map(rescue,todo):
            for i,old in enumerate(rows):
                if old['title']==row['title']:rows[i]=row;break
            if row['state']=='page_ok':rescued.append(row['title'])
            print('Alias recheck:',row['title'],row['state'],flush=True)
    # The first audit also included no-Referer probes. Recheck uncertain media with
    # the episode Referer used by the actual player; stop retrying a failing host.
    failures=collections.Counter()
    def recheck_media(row):
        for check in row['sources']:
            sample=check.get('sample');media=check.get('media',{})
            if not sample or not media.get('url') or media.get('state')=='http_ok':continue
            host=audit.urlsplit(media['url']).netloc
            with audit.lock:skip=failures[host]>=3
            if skip:continue
            meta,content=audit.request(media['url'],rules[check['source']],media=True,referer=sample['episode'])
            check['media']=dict(meta)
            if meta['status'] in (200,206):
                check['media']['state']='http_ok' if '.m3u8' not in audit.urlsplit(media['url']).path.lower() or content.lstrip('\ufeff\r\n ').startswith('#EXTM3U') else 'unexpected_content'
            elif meta['confirmed_dead']:check['media']['state']='dead';check['state']='unknown';check['reason']='sample_media_404_or_410'
            else:
                check['media']['state']='unknown'
                with audit.lock:failures[host]+=1
        states=[check['state'] for check in row['sources']]
        row['state']='page_ok' if 'page_ok' in states else ('no_results' if all(s=='no_results' for s in states) else 'unknown')
        return row
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:rows=list(pool.map(recheck_media,rows))
    # Confirm every deletion again with the current checker, including old-run records.
    dead=[]
    for event in list(audit.events):
        if event.get('confirmed_dead'):
            meta,_=audit.request(event['url'],{},media=event['kind']=='media',referer=event.get('request_referer'))
            if meta['confirmed_dead']:dead.append(event['url'])
    queries={}
    for row in rows:
        for check in row['sources']:
            if check['state']=='page_ok':
                good=[s for s in check['searches'] if s.get('results',0)>0]
                if good and good[-1]['term']!=row['title']:queries.setdefault(row['title'],{})[check['source']]=good[-1]['term']
    now=datetime.datetime.now(datetime.timezone.utc)
    policy={'generated_at':now.isoformat(),'expires_at_ms':int((time.time()+7*86400)*1000),'scope':'Search/detail/sample episode + extracted media HTTP status; not full video playback','blocked_urls':list(dict.fromkeys(dead)),'hidden_titles':[row['title'] for row in rows if row['state']=='no_results'],'search_terms':queries}
    summary={'checked':431,'counts':dict(collections.Counter(row['state'] for row in rows)),'confirmed_404_410_urls':len(policy['blocked_urls']),'hidden_titles':policy['hidden_titles'],'rescued_aliases':rescued,'corrected_search_names':len(queries),'http_check_records':len(audit.events),'http_statuses':dict(collections.Counter(str(event['status']) for event in audit.events)),'checked_at':now.isoformat(),'sources':{}}
    for key in rules:
        checks=[check for row in rows for check in row['sources'] if check['source']==key]
        summary['sources'][key]={'name_checks':dict(collections.Counter(check['state'] for check in checks)),'sample_media_checks':dict(collections.Counter(check.get('media',{}).get('state','not_extracted') for check in checks))}
    for name,data in [('results.json',rows),('requests.json',audit.events),('summary.json',summary),('link-health.json',policy)]:
        (out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    (audit.ROOT/'assets/link-health.json').write_text(json.dumps(policy,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':main()
