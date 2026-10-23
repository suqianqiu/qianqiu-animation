package com.qianq.kandongman;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.parser.Parser;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

/** Native implementation; source JSON formats credited to KazumiRules (MIT). */
public final class Sources {
    public static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36";
    public static final class Source {
        public final String id, name;
        public final JSONObject rule;
        Source(String id, String name, JSONObject rule) { this.id=id; this.name=name; this.rule=rule; }
        public String base() { return rule.optString("baseURL"); }
        public String agent() { String a=rule.optString("userAgent"); return a.isEmpty()?UA:a; }
    }
    public static final class Item {
        public final String name, url;
        public Item(String name, String url) { this.name=name; this.url=url; }
    }
    public static final class Road {
        public final String name;
        public final ArrayList<Item> episodes;
        Road(String name, ArrayList<Item> episodes) { this.name=name; this.episodes=episodes; }
    }
    public static ArrayList<Source> load(Context context) throws Exception {
        CompatTls.initialize(context);
        LinkHealth.initialize(context);
        ArrayList<Source> result=new ArrayList<Source>();
        String[][] names={{"MXdm","MX动漫"},{"akianime","Aki动漫"},{"xfdmneo","稀饭动漫"}};
        for(String[] n:names) try(InputStream in=context.getAssets().open(n[0]+".json")) {
            result.add(new Source(n[0],n[1],new JSONObject(read(in,2*1024*1024))));
        }
        return result;
    }
    static String read(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192];int n;
        long deadline=System.currentTimeMillis()+20000;
        while((n=input.read(buf))!=-1) {
            if(out.size()+n>limit) throw new IOException("页面过大");
            if(Thread.currentThread().isInterrupted()||System.currentTimeMillis()>deadline) throw new IOException("读取超时");
            out.write(buf,0,n);
        }
        return new String(out.toByteArray(),"UTF-8");
    }
    public static boolean http(String url) {
        try { URI u=new URI(url); return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null&&u.getUserInfo()==null; }
        catch(Exception e) {return false;}
    }
    public static String absolute(String base,String value) throws IOException {
        try {String s=new URL(new URL(base),Parser.unescapeEntities(value,false)).toString(); if(!http(s))throw new IOException("地址格式不支持");return s;}
        catch(MalformedURLException e){throw new IOException("无效来源地址",e);}
    }
    public static String fetch(Source source,String address) throws Exception {
        String requested=address;
        Set<String> missingOnce=new HashSet<String>();
        for(int redirects=0;redirects<5;redirects++) {
            if(!http(address))throw new IOException("仅支持HTTP或HTTPS地址");
            if(LinkHealth.isBlocked(address)){if(!requested.equals(address))LinkHealth.markDead(requested);throw new IOException("地址已失效（确认404/410），已自动过滤");}
            HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
            if(c instanceof javax.net.ssl.HttpsURLConnection&&CompatTls.factory()!=null)
                ((javax.net.ssl.HttpsURLConnection)c).setSSLSocketFactory(CompatTls.factory());
            c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",source.agent());c.setRequestProperty("Accept-Encoding","gzip");
            String ref=source.rule.optString("referer");if(!ref.isEmpty())c.setRequestProperty("Referer",ref);
            try {
                int code=c.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308) {address=absolute(address,c.getHeaderField("Location"));continue;}
                if(code==404||code==410){if(missingOnce.add(address))continue;LinkHealth.markDead(address);LinkHealth.markDead(requested);throw new IOException("地址已失效（HTTP "+code+"），已自动过滤");}
                if(code!=200)throw new IOException("源站返回HTTP "+code);
                InputStream stream=c.getInputStream();
                if("gzip".equalsIgnoreCase(c.getContentEncoding()))stream=new GZIPInputStream(stream);
                try(InputStream in=stream){return read(in,3*1024*1024);}
            } finally {c.disconnect();}
        }
        throw new IOException("重定向次数过多");
    }
    static org.w3c.dom.Document document(String text) {
        org.jsoup.nodes.Document doc=Jsoup.parse(text);
        doc.select("[xmlns]").removeAttr("xmlns");
        return new W3CDom().fromJsoup(doc);
    }
    static NodeList nodes(Node node,String path) throws Exception {
        if(!(node instanceof org.w3c.dom.Document)&&path.startsWith("//"))path="."+path;
        return (NodeList)XPathFactory.newInstance().newXPath().evaluate(path,node,XPathConstants.NODESET);
    }
    static String attr(Node node,String name) {
        if(node==null||node.getAttributes()==null)return "";
        Node a=node.getAttributes().getNamedItem(name);return a==null?"":a.getNodeValue();
    }
    public static ArrayList<Item> search(Source source,String query) throws Exception {
        String url=source.rule.getString("searchURL").replace("@keyword",URLEncoder.encode(query,"UTF-8"));
        return parseSearch(source,fetch(source,url));
    }
    public static ArrayList<Item> parseSearch(Source source,String raw) throws Exception {
        org.w3c.dom.Document root=document(raw);NodeList list=nodes(root,source.rule.getString("searchList"));
        ArrayList<Item> result=new ArrayList<Item>();Set<String> seen=new HashSet<String>();
        for(int i=0;i<list.getLength()&&result.size()<150;i++) {
            NodeList names=nodes(list.item(i),source.rule.getString("searchName"));
            NodeList urls=nodes(list.item(i),source.rule.getString("searchResult"));
            if(names.getLength()==0||urls.getLength()==0)continue;
            String name=names.item(0).getTextContent().replaceAll("\\s+"," ").trim(),href=attr(urls.item(0),"href");
            if(name.isEmpty()||href.isEmpty())continue;
            String address=absolute(source.base(),href);
            if(!LinkHealth.isBlocked(address)&&seen.add(address))result.add(new Item(name+(MediaLabels.mandarin(list.item(i).getTextContent())&&!MediaLabels.mandarin(name)?"（国语）":""),address));
        }
        return result;
    }
    public static ArrayList<Road> chapters(Source source,String address) throws Exception {return parseChapters(source,fetch(source,address));}
    public static ArrayList<Road> parseChapters(Source source,String raw) throws Exception {
        org.w3c.dom.Document root=document(raw);NodeList list=nodes(root,source.rule.getString("chapterRoads"));
        ArrayList<Road> roads=new ArrayList<Road>();
        for(int i=0;i<list.getLength()&&roads.size()<30;i++) {
            NodeList episodes=nodes(list.item(i),source.rule.getString("chapterResult"));
            ArrayList<Item> items=new ArrayList<Item>();Set<String> seen=new HashSet<String>();
            for(int j=0;j<episodes.getLength()&&items.size()<2500;j++) {
                Node n=episodes.item(j);String href=attr(n,"href");if(href.isEmpty())continue;
                String url=absolute(source.base(),href);String title=n.getTextContent().replaceAll("\\s+","").trim();
                if(title.isEmpty())title="第"+(items.size()+1)+"集";
                if(!LinkHealth.isBlocked(url)&&seen.add(url))items.add(new Item(title,url));
            }
            if(!items.isEmpty()){
                String roadName="线路 "+(roads.size()+1);org.jsoup.nodes.Document html=Jsoup.parse(raw);org.jsoup.select.Elements tabs=html.select(".module-tab-item, .play_source_tab a, .nav-tabs li, .play-source li");if(tabs.size()==list.getLength()&&i<tabs.size()){String label=tabs.get(i).text().trim();if(!label.isEmpty()&&label.length()<60)roadName=label;}
                roads.add(new Road(roadName+" · "+items.size()+" 集",items));
            }
        }
        return roads;
    }
    public static boolean media(String url) {return http(url)&&Pattern.compile("\\.(m3u8|mp4|m4v|webm|mpd)(?:[?#]|$)",Pattern.CASE_INSENSITIVE).matcher(url).find();}
    static String jsonObject(String input,int start) {
        boolean quoted=false,escaped=false;int depth=0;
        for(int i=start;i<input.length();i++) {
            char c=input.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"')quoted=true;else if(c=='{')depth++;else if(c=='}'&&--depth==0)return input.substring(start,i+1);
        }
        return "";
    }
    /** Extract ordinary public player configuration; no credential or DRM handling. */
    public static String extractMedia(String text) throws Exception {
        Matcher object=Pattern.compile("player_[a-zA-Z0-9_]+\\s*=\\s*(\\{)").matcher(text);
        if(object.find()) {
            JSONObject player=new JSONObject(jsonObject(text,object.start(1)));
            String url=player.optString("url");int encoding=player.optInt("encrypt",0);
            if(encoding==1)url=URLDecoder.decode(url,"UTF-8");
            else if(encoding==2)url=URLDecoder.decode(new String(Base64.decode(url,Base64.DEFAULT),"UTF-8"),"UTF-8");
            else if(encoding!=0)url="";
            if(media(url))return url;
        }
        org.jsoup.nodes.Document doc=Jsoup.parse(text);
        for(org.jsoup.nodes.Element e:doc.select("video[src],source[src]")) {String u=e.attr("src");if(media(u))return u;}
        Matcher literal=Pattern.compile("(?:[\"']?(?:url|file|src)[\"']?)\\s*[:=]\\s*[\"'](https?[^\"'<>\\s]+)[\"']",Pattern.CASE_INSENSITIVE).matcher(text);
        while(literal.find()) {String u=Parser.unescapeEntities(literal.group(1).replace("\\/","/").replace("\\u0026","&"),false);if(media(u))return u;}
        return null;
    }
    public static String resolve(Source source,String page) throws Exception {
        if(media(page))return checkMedia(source,page,page);
        String raw=fetch(source,page);String video=extractMedia(raw);if(video!=null)return checkMedia(source,video,page);
        org.jsoup.nodes.Document doc=Jsoup.parse(raw,page);
        int tried=0;
        for(org.jsoup.nodes.Element frame:doc.select("iframe[src]")) {
            String src=frame.attr("src");if(src.isEmpty()||!http(frame.absUrl("src")))continue;
            if(++tried>2)break;
            String nested=fetch(source,frame.absUrl("src"));video=extractMedia(nested);if(video!=null)return checkMedia(source,video,page);
        }
        return null;
    }
    /** Small ranged GET; other errors remain playable candidates for the native player. */
    public static String checkMedia(Source source,String original,String referer) throws Exception {
        String address=original;Set<String> missingOnce=new HashSet<String>();
        for(int step=0;step<6;step++){
            if(LinkHealth.isBlocked(address)||LinkHealth.isBlocked(original)){if(http(referer))LinkHealth.markDead(referer);throw new IOException("视频地址已失效（404/410），请换线路");}
            if(!http(address))throw new IOException("无效视频地址");
            HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
            if(c instanceof javax.net.ssl.HttpsURLConnection&&CompatTls.factory()!=null)((javax.net.ssl.HttpsURLConnection)c).setSSLSocketFactory(CompatTls.factory());
            c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",source.agent());c.setRequestProperty("Range","bytes=0-0");
            c.setRequestProperty("Referer",source.rule.optString("referer").isEmpty()?referer:source.rule.optString("referer"));
            int code;
            try{code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){address=absolute(address,c.getHeaderField("Location"));continue;}}
            catch(IOException temporary){return original;}
            finally{c.disconnect();}
            if(code==404||code==410){if(missingOnce.add(address))continue;LinkHealth.markDead(address);LinkHealth.markDead(original);if(http(referer))LinkHealth.markDead(referer);throw new IOException("视频地址已失效（HTTP "+code+"），已自动过滤");}
            return original;
        }
        return original;
    }
}
