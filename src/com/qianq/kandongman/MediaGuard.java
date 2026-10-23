package com.qianq.kandongman;

import java.net.*;
import java.util.*;
import java.util.regex.*;

/** Conservative URL rules: block identifiable ad endpoints, never guess from a CDN hash. */
final class MediaGuard {
    private static final Pattern PATH=Pattern.compile("(?:^|/)(?:ads?|advert(?:isement)?s?|preroll|midroll|vast|vpaid|popunder|popups?)(?:/|[._-]|$)",Pattern.CASE_INSENSITIVE);
    static boolean blocked(String address){
        try{
            URI u=new URI(address);String scheme=u.getScheme();
            if(!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))return true;
            String host=u.getHost();if(host==null||u.getUserInfo()!=null)return true;host=host.toLowerCase(Locale.US);
            for(String suffix:new String[]{"doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com","adnxs.com","adsrvr.org"})if(host.equals(suffix)||host.endsWith("."+suffix))return true;
            if(host.matches("(?:ads?|adserver|advertising)\\..*"))return true;
            String path=u.getPath()==null?"":u.getPath();if(PATH.matcher(path).find())return true;
            String query=u.getQuery();return query!=null&&Pattern.compile("(?:^|&)(?:ad|is_ad|advertisement)=(?:1|true)(?:&|$)",Pattern.CASE_INSENSITIVE).matcher(query).find();
        }catch(Exception e){return true;}
    }
    static boolean sameDocument(String page,String candidate){
        try{URI a=new URI(page),b=new URI(candidate);return !blocked(candidate)&&Objects.equals(a.getScheme(),b.getScheme())&&Objects.equals(a.getAuthority(),b.getAuthority())&&Objects.equals(a.getPath(),b.getPath())&&Objects.equals(a.getQuery(),b.getQuery());}catch(Exception e){return false;}
    }
    static void check(String address)throws java.io.IOException{if(blocked(address))throw new java.io.IOException("已拦截广告或不安全跳转地址，请更换线路");}
    static void checkPlaylist(String base,String text)throws Exception{
        Pattern uri=Pattern.compile("URI=\"([^\"]+)\"");
        for(String line:text.replace("\r","").split("\n")){
            line=line.trim();if(!line.isEmpty()&&!line.startsWith("#"))check(HlsCache.absolute(base,line));
            Matcher m=uri.matcher(line);while(m.find())check(HlsCache.absolute(base,m.group(1)));
        }
    }
}
