package com.qianq.kandongman;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/** A VOD playlist rewritten to immutable local resource names. */
final class HlsCache {
    interface Fetch { String get(String url) throws Exception; }
    final ArrayList<String> urls=new ArrayList<String>();
    String playlist;
    static HlsCache parse(String address,Fetch fetch) throws Exception {
        String text="";
        for(int depth=0;depth<5;depth++) {
            text=fetch.get(address).replace("\r","").trim();
            if(!text.startsWith("#EXTM3U"))throw new IOException("不是有效的 HLS 播放列表");
            String[] lines=text.split("\n");String variant=null;
            for(int i=0;i<lines.length;i++)if(lines[i].startsWith("#EXT-X-STREAM-INF:")){
                if(lines[i].contains("AUDIO="))throw new IOException("独立音轨 HLS 暂不支持下载，请使用在线播放");
                for(int j=i+1;j<lines.length;j++)if(!lines[j].trim().isEmpty()&&!lines[j].startsWith("#")){variant=absolute(address,lines[j].trim());break;}
                break;
            }
            if(variant==null)break;
            address=variant;
            if(depth==4)throw new IOException("HLS 播放列表嵌套过深");
        }
        if(!text.contains("#EXT-X-ENDLIST"))throw new IOException("直播或未完结的 HLS 暂不支持下载");
        if(text.contains("#EXT-X-BYTERANGE")||text.contains("BYTERANGE="))throw new IOException("字节范围 HLS 暂不支持下载");
        HlsCache result=new HlsCache();StringBuilder out=new StringBuilder();
        Pattern uri=Pattern.compile("URI=\"([^\"]+)\"");
        for(String raw:text.split("\n")) {
            String line=raw.trim();
            if(line.startsWith("#EXT-X-KEY:")&&!line.contains("METHOD=NONE")&&(!line.contains("METHOD=AES-128")||line.contains("KEYFORMAT=")))throw new IOException("此加密格式不支持本地缓存");
            if(line.startsWith("#")) {
                Matcher m=uri.matcher(line);StringBuffer changed=new StringBuffer();
                while(m.find())m.appendReplacement(changed,Matcher.quoteReplacement("URI=\""+result.add(absolute(address,m.group(1)))+"\""));
                m.appendTail(changed);out.append(changed);
            } else if(!line.isEmpty())out.append(result.add(absolute(address,line)));
            out.append('\n');
        }
        if(result.urls.isEmpty())throw new IOException("没有可缓存的视频片段");
        result.playlist=out.toString();return result;
    }
    private String add(String url){int n=urls.indexOf(url);if(n<0){n=urls.size();urls.add(url);}return "r"+n;}
    static String absolute(String base,String value)throws Exception{
        URL u=new URL(new URL(base),value);
        if(!u.getProtocol().equals("http")&&!u.getProtocol().equals("https"))throw new IOException("不支持的资源协议");
        if(u.getUserInfo()!=null)throw new IOException("不支持的资源地址");
        return u.toString();
    }
}
