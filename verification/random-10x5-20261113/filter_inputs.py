from pathlib import Path
import json,hashlib
P=Path(__file__).resolve().parent
rows=json.loads((P/'resolved.json').read_text('utf-8'))['cases'];resources=json.loads((P/'resources.json').read_text('utf-8'))['resources']
(P/'index.tsv').write_text(''.join(x['route']['id']+'\t'+x['route']['manifest']+'\t'+x['route']['page']+'\n' for x in rows if x['status']=='resolved'),encoding='utf-8')
(P/'digest-map.tsv').write_text(''.join(x['url']+'\t'+x['sha256']+'\n' for x in resources if x['status']=='downloaded'),encoding='utf-8')
apk=P.parents[1]/'dist/KanDongman-1.6.8.apk';rules=P.parents[1]/'assets/known-hls-ads.tsv'
(P/'tested-build.json').write_text(json.dumps({'version':'1.6.8','apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'rules_sha256':hashlib.sha256(rules.read_bytes()).hexdigest(),'rules_not_modified_for_random_sample':True,'diagnostic_probes':'complete downloaded files; no simulated network deadline; not a TV playback performance benchmark'},indent=2),encoding='utf-8')
