from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,hashlib,re,unicodedata,math
P=Path(__file__).resolve().parent
plan=json.loads((P/'frame-plan.json').read_text('utf-8'));frames=json.loads((P/'frames.json').read_text('utf-8'))['resources']
ocr=json.loads((P/'ocr.json').read_text('utf-8')) if (P/'ocr.json').exists() else []
if isinstance(ocr,dict):ocr=[ocr]
texts={x['file']:x.get('text','') for x in ocr}
known=set()
for line in (P.parents[1]/'assets/known-hls-ads.tsv').read_text('utf-8').splitlines():
    if line.startswith('segment\t'):known.add(line.split('\t')[2])
    if line.startswith('sequence\t'):known.update(x.split(':')[1] for x in line.split('\t')[1].split(','))
context={}
for c in plan['cases']:
    for s in c['samples']:context.setdefault(s['key'],[]).append({'title':c['title'],'episode':c['episode'],'time':s['start'],'index':s['index']})
groups={}
for item in frames:
    if item.get('frame_status')!='decoded':continue
    image=Image.open(P/item['frame']).convert('RGB');pixelhash=hashlib.sha256(str(image.size).encode()+image.tobytes()).hexdigest()
    text=texts.get(item['frame'],'');plain=re.sub(r'\s+','',unicodedata.normalize('NFKC',text)).lower()
    hits=re.findall(r'开元棋牌|棋牌|新葡京|威尼斯人|注册.{0,10}送|免费.{0,6}试玩|扫码|澳门|pg电子|本片由|赞助播出|\d{3,8}[.。]com|www[.。]',plain)
    row=groups.setdefault(pixelhash,{'pixel_sha256':pixelhash,'file':item['frame'],'resources':[],'contexts':[],'texts':[],'hits':[],'known_ad_bytes':True,'dark':item.get('mean_brightness',0)<8})
    row['resources'].append(item['key']);row['contexts']+=context.get(item['key'],[])
    if text and text not in row['texts']:row['texts'].append(text)
    row['hits']=sorted(set(row['hits']+hits));row['known_ad_bytes']&=item['sha256'] in known
save=P/'review-index.json';previous=json.loads(save.read_text('utf-8')) if save.exists() else [];assigned={x['pixel_sha256']:x['review_id'] for x in previous}
new=[x for x in groups.values() if x['pixel_sha256'] not in assigned]
new.sort(key=lambda x:(not bool(x['hits']),x['known_ad_bytes'],x['file']))
counter=len(assigned)
for x in new:counter+=1;assigned[x['pixel_sha256']]=f'R{counter:03}'
rows=[]
for row in groups.values():row['review_id']=assigned[row['pixel_sha256']];rows.append(row)
rows.sort(key=lambda x:x['review_id']);save.write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',12);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',11)
unknown=[r for r in rows if not r['known_ad_bytes']]
for page in range(math.ceil(len(unknown)/24)):
    batch=unknown[page*24:page*24+24];sheet=Image.new('RGB',(1440,760),(20,23,36));draw=ImageDraw.Draw(sheet)
    for j,r in enumerate(batch):
        x=(j%6)*240;y=(j//6)*190;im=Image.open(P/r['file']);im.thumbnail((236,137));sheet.paste(im,(x,y))
        c=r['contexts'][0] if r['contexts'] else {'title':'unknown','episode':0,'time':0}
        draw.text((x+3,y+137),r['review_id']+' '+c['title'][:9]+' '+str(c['episode']),font=font,fill='white')
        draw.text((x+3,y+153),f"{c['time']:.1f}s "+','.join(r['hits'])[:18],font=small,fill='#FFCA83' if r['hits'] else '#AABBDD')
        draw.text((x+3,y+168),r['file'][:16],font=small,fill='#94A0AF')
    sheet.save(P/f'review-{page+1:02}.jpg',quality=94)
print('resources',sum(len(r['resources']) for r in rows),'unique images',len(rows),'known-ad images',sum(r['known_ad_bytes'] for r in rows),'manual review',len(unknown),'grids',math.ceil(len(unknown)/24),'OCR flagged',sum(bool(r['hits']) for r in unknown))
