import importlib.util, json, unittest
from pathlib import Path
import requests

spec=importlib.util.spec_from_file_location('catalog_audit',Path(__file__).resolve().parents[1]/'verification/audit_catalog.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class Response:
    def __init__(self,status):self.status_code=status;self.url='https://audit.test/item';self.headers={'Content-Type':'text/plain'}
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def iter_content(self,size):yield b'#EXTM3U\n'

class Session:
    def __init__(self,states):self.states=iter(states);self.calls=0
    def get(self,*args,**kwargs):
        self.calls+=1;state=next(self.states)
        if state is None:raise requests.Timeout('simulated timeout')
        return Response(state)

class AuditTest(unittest.TestCase):
    def setUp(self):audit.cache.clear();audit.hosts.clear();audit.events.clear();audit.time.sleep=lambda seconds:None
    def probe(self,states):
        audit.local.session=Session(states)
        return audit.request('https://audit.test/item',{})[0]
    def test_permanent_requires_two_responses(self):
        self.assertTrue(self.probe([404,404])['confirmed_dead'])
    def test_recovery(self):self.assertFalse(self.probe([404,200])['confirmed_dead'])
    def test_timeout_then_404_is_not_confirmation(self):self.assertFalse(self.probe([None,404])['confirmed_dead'])
    def test_auth_not_permanent(self):self.assertFalse(self.probe([403])['confirmed_dead'])
    def test_artplayer_literal(self):
        self.assertEqual(audit.extract_media("var art = new Artplayer({url: 'https://video.test/index.m3u8', type:'m3u8'});"),'https://video.test/index.m3u8')
    def test_player_json(self):
        body='var player_x='+json.dumps({'url':'https://video.test/a.m3u8','encrypt':0})
        self.assertEqual(audit.extract_media(body),'https://video.test/a.m3u8')
    def test_dom_xpath(self):
        rule={'baseURL':'https://audit.test','searchList':'//li','searchName':'//a','searchResult':'//a','chapterRoads':'//ul','chapterResult':'//li/a'}
        body='<ul><li><a href="/item">name</a></li></ul>'
        self.assertEqual(audit.parse_search(rule,body),[{'name':'name','url':'https://audit.test/item'}])
        self.assertEqual(audit.parse_roads(rule,body)[0][0]['url'],'https://audit.test/item')

if __name__=='__main__':unittest.main()
