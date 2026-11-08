package com.qianq.kandongman;
import java.util.*;
import org.json.*;
public final class PosterSourceTest {
    public static void main(String[] args)throws Exception{
        JSONObject rule=new JSONObject().put("baseURL","https://example.org").put("searchList","//li").put("searchName","//h3/a").put("searchResult","//h3/a");
        Sources.Source source=new Sources.Source("fixture","fixture",rule);
        String html="<ul><li><img src='/webjs/load.gif' data-original='//covers.example.org/naruto.jpg'><h3><a href='/detail/1'>火影忍者</a></h3></li><li><img src='data:image/png;base64,xxx' data-src='/covers/onepiece.jpg'><h3><a href='/detail/2'>海贼王</a></h3></li><li><img src='https://ads.example.org/ad.jpg'><h3><a href='/detail/3'>龙珠</a></h3></li></ul>";
        ArrayList<Sources.Item> result=Sources.parseSearch(source,html);
        if(result.size()!=3||!result.get(0).cover.equals("https://covers.example.org/naruto.jpg")||!result.get(1).cover.equals("https://example.org/covers/onepiece.jpg")||!result.get(2).cover.isEmpty())throw new AssertionError("cover parsing");
        if(!result.get(0).url.equals("https://example.org/detail/1"))throw new AssertionError("search result regression");
        System.out.println("PASS: lazy image attributes, protocol-relative and relative cover URLs, placeholder/ad rejection and search links");
    }
}
