package com.qianq.kandongman;
import com.sun.net.httpserver.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class LinkHealthTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        long now=System.currentTimeMillis();
        LinkHealth.applyPolicy(new JSONObject().put("expires_at_ms",now+60000).put("blocked_urls",new JSONArray().put("https://example.test/dead")).put("hidden_titles",new JSONArray().put("missing title")).put("search_terms",new JSONObject().put("航海王",new JSONObject().put("test","海贼王"))),now);
        check(LinkHealth.isBlocked("https://example.test/dead"),"Snapshot URL not excluded");
        check(LinkHealth.hideTitle("missing title"),"Unavailable catalogue title not hidden");
        check(LinkHealth.searchTerm("航海王","test").equals("海贼王"),"Validated source alias not used");
        check(LinkHealth.searchTerm("航海王","other").equals("航海王"),"Unknown source query changed");
        LinkHealth.applyPolicy(new JSONObject().put("expires_at_ms",now-1).put("blocked_urls",new JSONArray().put("https://example.test/dead")).put("hidden_titles",new JSONArray().put("missing title")),now);
        check(!LinkHealth.isBlocked("https://example.test/dead")&&!LinkHealth.hideTitle("missing title"),"Expired audit must not permanently hide entries");
        check(LinkHealth.searchTerm("航海王","test").equals("航海王"),"Expired source alias still applied");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger missing=new AtomicInteger(),flaky=new AtomicInteger();
        server.createContext("/missing",e->{missing.incrementAndGet();e.sendResponseHeaders(404,-1);e.close();});
        server.createContext("/gone",e->{e.sendResponseHeaders(410,-1);e.close();});
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","/redirect-target");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/redirect-target",e->{e.sendResponseHeaders(404,-1);e.close();});
        server.createContext("/forbidden",e->{e.sendResponseHeaders(403,-1);e.close();});
        server.createContext("/busy",e->{e.sendResponseHeaders(503,-1);e.close();});
        server.createContext("/flaky",e->{int count=flaky.incrementAndGet();byte[] b="ok".getBytes();e.sendResponseHeaders(count==1?404:200,count==1?-1:b.length);if(count!=1)e.getResponseBody().write(b);e.close();});
        server.createContext("/video",e->{check("bytes=0-0".equals(e.getRequestHeaders().getFirst("Range")),"Media probe must use bounded range");e.sendResponseHeaders(206,1);e.getResponseBody().write(1);e.close();});
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        Sources.Source source=new Sources.Source("test","test",new JSONObject().put("baseURL",base));
        try{
            try{Sources.fetch(source,base+"/missing");throw new AssertionError("Missing URL accepted");}catch(java.io.IOException expected){}
            check(missing.get()==2&&LinkHealth.isBlocked(base+"/missing"),"404 requires two confirmations");
            try{Sources.fetch(source,base+"/missing");}catch(java.io.IOException expected){}
            check(missing.get()==2,"Blocked page fetched again");
            try{Sources.fetch(source,base+"/redirect");throw new AssertionError("Redirected 404 accepted");}catch(java.io.IOException expected){}
            check(LinkHealth.isBlocked(base+"/redirect")&&LinkHealth.isBlocked(base+"/redirect-target"),"Redirected dead URL not removed from original list");
            check(Sources.fetch(source,base+"/flaky").equals("ok")&&!LinkHealth.isBlocked(base+"/flaky"),"Single 404 must not delete a recovered page");
            for(String path:new String[]{"/forbidden","/busy"}){try{Sources.fetch(source,base+path);}catch(java.io.IOException expected){}check(!LinkHealth.isBlocked(base+path),"Transient HTTP must not be blacklisted");}
            try{Sources.checkMedia(source,base+"/gone",base+"/episode");throw new AssertionError("410 media accepted");}catch(java.io.IOException expected){}
            check(LinkHealth.isBlocked(base+"/gone")&&LinkHealth.isBlocked(base+"/episode"),"Missing media/episode not excluded");
            check(Sources.checkMedia(source,base+"/forbidden",base+"/page").equals(base+"/forbidden")&&!LinkHealth.isBlocked(base+"/page"),"403 must remain uncertain");
            check(Sources.checkMedia(source,base+"/video",base+"/page").equals(base+"/video"),"206 media rejected");
            source=new Sources.Source("test","test",new JSONObject().put("baseURL",base).put("searchList","//li").put("searchName","//a").put("searchResult","//a").put("chapterRoads","//ul").put("chapterResult","//li/a"));
            String page="<ul><li><a href='/missing'>dead</a></li><li><a href='/video'>good</a></li></ul>";
            check(Sources.parseSearch(source,page).size()==1,"Search includes dead URL");
            check(Sources.parseChapters(source,page).get(0).episodes.size()==1,"Episodes include dead URL");
            System.out.println("PASS: snapshot expiry, 404/410 confirmations, recovered 404, 403/503 preservation, ranged media GET, search/episode filtering");
        }finally{server.stop(0);}
    }
}
