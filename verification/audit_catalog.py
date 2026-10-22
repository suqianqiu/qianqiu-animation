"""Bounded HTTP audit. Run on the computer; never downloads whole videos.

Tests each catalogue name against each source; samples one usable result and one
episode per source/name. Confirmed 404/410 URLs are exported for app filtering.
403, 429, TLS and timeouts are indeterminate, never permanent deletion evidence.
"""
import argparse, collections, concurrent.futures, datetime, json, re, threading, time
from pathlib import Path
from urllib.parse import urljoin, urlsplit, quote
import requests
from lxml import html

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'verification'/'catalog-audit'
UA='Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36'
lock=threading.Lock(); local=threading.local(); cache={}; hosts={}; events=[]; media_failures=collections.Counter()

def session():
    if not hasattr(local,'session'): local.session=requests.Session()
    return local.session

def request(url, rule, media=False, referer=None):
    ref=rule.get('referer') or (referer if media else None)
    key=(url,media,ref)
    with lock:
        previous=cache.get(key)
        if previous is None: previous=cache[key]=concurrent.futures.Future(); owner=True
        else: owner=False
    if not owner:return previous.result()
    host=urlsplit(url).netloc
    with lock:
        if host not in hosts:hosts[host]=[threading.Semaphore(2),threading.Lock(),0.0]
        gate, pacing, _=hosts[host]
    result={'url':url,'kind':'media' if media else 'page','status':None}
    if ref:result['request_referer']=ref
    body=''; statuses=[]
    try:
        for attempt in range(2):
            with gate:
                with pacing:
                    delay=hosts[host][2]-time.monotonic()
                    if delay>0:time.sleep(delay)
                    hosts[host][2]=time.monotonic()+0.4
                headers={'User-Agent':rule.get('userAgent') or UA}
                if ref:headers['Referer']=ref
                if media:headers['Range']='bytes=0-8191'
                try:
                    with session().get(url,headers=headers,timeout=(5,9),stream=True,allow_redirects=True) as response:
                        result.update(status=response.status_code,final_url=response.url,content_type=response.headers.get('Content-Type',''))
                        if response.status_code in (200,206):
                            chunks=[]; size=0; deadline=time.monotonic()+15
                            for chunk in response.iter_content(8192):
                                chunks.append(chunk);size+=len(chunk)
                                if size>=(8192 if media else 3*1024*1024) or time.monotonic()>deadline:break
                            raw=b''.join(chunks);body=raw.decode('utf-8','replace');result['bytes_read']=len(raw)
                        else:body=''
                except requests.RequestException as exc:
                    result.update(status=None,error=type(exc).__name__+': '+str(exc)[:220])
            statuses.append(result['status'])
            # A permanent response must reproduce, not just occur once.
            if result['status'] in (404,410) or result['status'] is None or result['status'] in (429,500,502,503,504):
                if attempt==0:time.sleep(1);continue
            break
        result['attempt_statuses']=statuses
        result['confirmed_dead']=len(statuses)==2 and all(code in (404,410) for code in statuses)
        result['checked_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        with lock:events.append(dict(result))
        answer=(result,body);previous.set_result(answer);return answer
    except BaseException as exc:
        previous.set_exception(exc);raise

def document(body):return html.fromstring(body)
def relative(node, xpath):return node.xpath('.'+xpath if xpath.startswith('//') else xpath)
def text(node):return ' '.join(node.itertext()).strip()
def parse_search(rule,body):
    result=[];seen=set()
    for row in document(body).xpath(rule['searchList']):
        names=relative(row,rule['searchName']);urls=relative(row,rule['searchResult'])
        if names and urls:
            link=urljoin(rule['baseURL'],urls[0].get('href',''));name=text(names[0])
            if name and link not in seen and urlsplit(link).scheme in ('http','https'):
                result.append({'name':name,'url':link});seen.add(link)
    return result
def parse_roads(rule,body):
    result=[]
    for road in document(body).xpath(rule['chapterRoads']):
        episodes=[]
        for node in relative(road,rule['chapterResult']):
            if node.get('href'):episodes.append({'name':text(node),'url':urljoin(rule['baseURL'],node.get('href'))})
        if episodes:result.append(episodes)
    return result
def extract_media(body):
    match=re.search(r'player_\w+\s*=\s*(\{)',body)
    if match:
        try:
            player,_=json.JSONDecoder().raw_decode(body[match.start(1):]);url=player.get('url','');encoding=player.get('encrypt',0)
            from urllib.parse import unquote
            if str(encoding)=='1':url=unquote(url)
            elif str(encoding)=='2':
                import base64
                url=unquote(base64.b64decode(url).decode())
            elif str(encoding)!='0':return None
            if re.search(r'\.(m3u8|mp4|m4v|webm|mpd)(?:[?#]|$)',url,re.I):return url
        except (ValueError,TypeError):pass
    import html as entities
    for match in re.finditer(r'''(?:["']?(?:url|file|src)["']?)\s*[:=]\s*["'](https?[^"'<>\s]+)["']''',body,re.I):
        url=entities.unescape(match.group(1).replace('\\/','/').replace('\\u0026','&'))
        if re.search(r'\.(m3u8|mp4|m4v|webm|mpd)(?:[?#]|$)',url,re.I):return url
    return None

def audit_source(entry,source_id,rule):
    result={'source':source_id,'state':'unknown','searches':[]}
    # Try Chinese aliases only if the original name has no result.
    terms=[entry['title']]+entry['aliases'][:2]
    found=[]
    for term in terms:
        url=rule['searchURL'].replace('@keyword',quote(term))
        meta,body=request(url,rule)
        search={'term':term,'url':url,'status':meta['status']};result['searches'].append(search)
        if meta['status']!=200:
            result['reason']='search_http_or_network';return result
        try:found=parse_search(rule,body)
        except Exception as exc:result['reason']='search_parse: '+str(exc);return result
        if entry.get('match'):
            found=[item for item in found if entry['match'].lower() in item['name'].lower()]
        search['results']=len(found)
        # An empty parsed set alone is not proof of an empty search page.
        if not found and not any(marker in body for marker in ('搜索','没有','暂无','search')):
            result['reason']='unrecognized_search_page';return result
        if found:break
    if not found:result['state']='no_results';return result
    result['results_count']=len(found)
    def score(item):return (item['name']!=entry['title'],len(item['name']))
    found.sort(key=score)
    result['candidates']=[]
    for item in found[:3]:
        detail={'name':item['name'],'url':item['url']};result['candidates'].append(detail)
        meta,body=request(item['url'],rule);detail['status']=meta['status']
        if meta['status']!=200:continue
        try:roads=parse_roads(rule,body)
        except Exception:continue
        detail['roads']=len(roads);detail['episodes']=sum(map(len,roads))
        if not roads:continue
        # Prefer the first episode, then another road if that link is dead.
        for road in roads[:2]:
            episode=road[0];page,page_body=request(episode['url'],rule)
            detail.setdefault('sample_episodes',[]).append({'url':episode['url'],'status':page['status']})
            if page['status']!=200:continue
            result.update(state='page_ok',sample={'detail':item['url'],'episode':episode['url'],'name':item['name']})
            media=extract_media(page_body)
            if media:
                host=urlsplit(media).netloc
                with lock:skip=media_failures[host]>=3
                if skip:
                    result['media']={'url':media,'state':'unknown_host_repeated_failures'}
                else:
                    video,playlist=request(media,rule,media=True,referer=episode['url'])
                    result['media']={k:v for k,v in video.items() if k!='error'}
                    if video['status'] in (200,206):
                        if '.m3u8' in urlsplit(media).path.lower() and not playlist.lstrip('\ufeff\r\n ').startswith('#EXTM3U'):
                            result['media']['state']='unexpected_content'
                        else:result['media']['state']='http_ok'
                    elif video['confirmed_dead']:
                        result['media']['state']='dead';continue
                    else:
                        result['media']['state']='unknown'
                        with lock:media_failures[host]+=1
            return result
    # Other results/episodes may still be usable, so this is not permanent exclusion.
    result['state']='unknown';result['reason']='sample_candidates_unavailable';return result

def audit(entry,rules):
    checks=[]
    for source_id,rule in rules.items():
        try:checks.append(audit_source(entry,source_id,rule))
        except Exception as exc:checks.append({'source':source_id,'state':'unknown','reason':repr(exc)})
    states=[check['state'] for check in checks]
    state='page_ok' if 'page_ok' in states else ('no_results' if all(s=='no_results' for s in states) else 'unknown')
    return {'title':entry['title'],'state':state,'sources':checks}

def backfill_media(row,rules):
    for check in row['sources']:
        if check['state']!='page_ok' or 'media' in check:continue
        rule=rules[check['source']]
        meta,body=request(check['sample']['episode'],rule)
        if meta['status']!=200:continue
        media=extract_media(body)
        if not media:check['media']={'state':'not_extracted'};continue
        video,content=request(media,rule,media=True,referer=check['sample']['episode'])
        check['media']=dict(video)
        if video['status'] in (200,206):
            check['media']['state']='http_ok' if '.m3u8' not in urlsplit(media).path.lower() or content.lstrip('\ufeff\r\n ').startswith('#EXTM3U') else 'unexpected_content'
        elif video['confirmed_dead']:
            check['media']['state']='dead';check['state']='unknown';check['reason']='sample_media_404_or_410'
        else:check['media']['state']='unknown'
    states=[check['state'] for check in row['sources']]
    row['state']='page_ok' if 'page_ok' in states else ('no_results' if all(s=='no_results' for s in states) else 'unknown')
    return row

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--limit',type=int,default=0);parser.add_argument('--workers',type=int,default=8);parser.add_argument('--resume',action='store_true');args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    rules={key:json.loads((ROOT/'assets'/f'{key}.json').read_text('utf-8-sig')) for key in ('MXdm','akianime','xfdmneo')}
    raw=(ROOT/'src/com/qianq/kandongman/AnimeCatalog.java').read_text('utf-8')
    entries=[]
    for row in re.findall(r'^\s*"([^"\n]+\|[^"\n]+)"',raw,re.M):
        fields=row.split('|');entries.append({'title':fields[0],'aliases':list(dict.fromkeys(f for f in fields[2:] if re.search(r'[\u4e00-\u9fff]',f) and f!=fields[0]))})
    if args.limit:entries=entries[:args.limit]
    results=[];started=time.time();completed_tasks=0
    if args.resume and (OUT/'results.json').exists():
        results=json.loads((OUT/'results.json').read_text('utf-8'))
        events.extend(json.loads((OUT/'requests.json').read_text('utf-8')))
    existing={row['title'] for row in results}
    pending=[entry for entry in entries if entry['title'] not in existing]
    legacy=[row for row in results if any(check['state']=='page_ok' and 'media' not in check for check in row['sources'])]
    total_tasks=len(pending)+len(legacy)
    def save():
        counts=dict(collections.Counter(row['state'] for row in results))
        summary={'checked':len(results),'total':len(entries),'tasks_completed':completed_tasks,'tasks_total':total_tasks,'counts':counts,'requests':len(events),'elapsed_seconds':round(time.time()-started),'updated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
        (OUT/'progress.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
        (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),'utf-8')
        (OUT/'requests.json').write_text(json.dumps(events,ensure_ascii=False,indent=2),'utf-8')
        return summary
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        tasks=[pool.submit(backfill_media,row,rules) for row in legacy]+[pool.submit(audit,entry,rules) for entry in pending]
        for task in concurrent.futures.as_completed(tasks):
            row=task.result();completed_tasks+=1
            if row['title'] in existing:
                for i,old in enumerate(results):
                    if old['title']==row['title']:results[i]=row;break
            else:results.append(row);existing.add(row['title'])
            if completed_tasks%10==0 or completed_tasks==total_tasks:print(json.dumps(save(),ensure_ascii=False),flush=True)
    summary=save()
    invalid=[event for event in events if event.get('confirmed_dead')]
    policy={'generated_at':summary['updated_at'],'scope':'HTTP checks of search/detail/sample episode/media; no full episode playback guarantee','blocked_urls':list(dict.fromkeys(event['url'] for event in invalid)),'hidden_titles':[row['title'] for row in results if row['state']=='no_results'],'catalog_status':{row['title']:row['state'] for row in results}}
    (OUT/'link-health.json').write_text(json.dumps(policy,ensure_ascii=False,indent=2),'utf-8')
    print('FINISHED '+json.dumps(summary,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
