"""Create a dated, attributable Douban rating snapshot from public JSON results."""
from pathlib import Path
import datetime,json,re,time,unicodedata
import requests
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'verification/douban';OUT.mkdir(parents=True,exist_ok=True)
SOURCES=[('japan_tv','https://movie.douban.com/j/search_subjects?type=tv&tag=%E6%97%A5%E6%9C%AC%E5%8A%A8%E7%94%BB&sort=rank&page_limit=100&page_start=0'),('animation_tv','https://movie.douban.com/j/search_subjects?type=tv&tag=%E5%8A%A8%E7%94%BB&sort=rank&page_limit=100&page_start=0'),('movies','https://movie.douban.com/j/chart/top_list?type=25&interval_id=100:90&action=&start=0&limit=100')]
def normalize(value):return ''.join(c for c in unicodedata.normalize('NFKC',value).lower() if c.isalnum())
def main():
    subjects=[]
    for key,url in SOURCES:
        response=requests.get(url,headers={'User-Agent':'Mozilla/5.0','Referer':'https://movie.douban.com/'},timeout=(5,15));response.raise_for_status();data=response.json()
        (OUT/(key+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
        rows=data if isinstance(data,list) else data['subjects'];subjects+=rows;print(key,len(rows),flush=True);time.sleep(.5)
    raw=(ROOT/'src/com/qianq/kandongman/AnimeCatalog.java').read_text('utf-8');catalog=[]
    for line in re.findall(r'^\s*"([^"\n]+\|[^"\n]+)"',raw,re.M):
        fields=line.split('|');aliases=[fields[0]]+[v for v in fields[2:] if re.search(r'[\u4e00-\u9fff]',v) or ' ' in v];catalog.append((fields[0],aliases))
    manual={'钢之炼金术师FA':'钢之炼金术师兄弟会','龙珠Z':'龙珠','3月的狮子 第二季':'三月的狮子','反叛的鲁路修R2':'叛逆的鲁路修','攻壳机动队S.A.C. 2nd GIG':'攻壳机动队'}
    best={};unmatched=[]
    for subject in subjects:
        title=subject['title'];rating=float(subject.get('rate') or subject.get('rating',[0])[0] or 0);target=manual.get(title);candidates=[]
        if target is None:
            for canonical,aliases in catalog:
                for alias in aliases:
                    key=normalize(alias);name=normalize(title)
                    boundary=title.startswith(alias+' ') or title.startswith(alias+'：') or title.startswith(alias+':')
                    if len(key)>=2 and (name==key or (name.startswith(key) and (len(key)>=3 or boundary))):candidates.append((len(key),canonical))
            target=max(candidates)[1] if candidates else None
        if not target:unmatched.append(title);continue
        if rating<8:continue
        row={'title':title,'catalogTitle':target,'rating':rating,'url':subject['url'],'votes':int(subject.get('vote_count',0))}
        if target not in best or (row['rating'],row['votes'])>(best[target]['rating'],best[target]['votes']):best[target]=row
    result={'fetched_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'date':datetime.datetime.now().strftime('%Y-%m-%d'),'method':'豆瓣公开动画高分结果中与本地目录匹配的作品；评分降序，同系列取最高分条目并显示具体作品/季名。非实时热度、非全量豆瓣排名。','sources':[url for _,url in SOURCES],'items':sorted(best.values(),key=lambda v:(-v['rating'],-v['votes'],v['title']))}
    assert len(result['items'])>=40,'Unexpectedly small snapshot'
    (ROOT/'assets/douban-popular.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print('Matched',len(best),'catalogue titles',flush=True)
if __name__=='__main__':main()
