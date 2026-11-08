package com.qianq.kandongman;

import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class PosterCatalogTest {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS: "+message);}
    public static void main(String[] args)throws Exception{
        JSONArray popular=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0],"assets/douban-popular.json")),"UTF-8")).getJSONArray("items");
        PosterCatalog model=new PosterCatalog(popular);List<String> none=Collections.emptyList();List<PosterCatalog.Item> all=model.list(0,"",'*',0,none);
        check(all.size()==464,"poster wall includes all 464 catalog titles");Set<String> titles=new HashSet<String>();int assets=0;for(PosterCatalog.Item item:all){titles.add(item.title);if(!item.asset.isEmpty())assets++;}
        check(titles.size()==464&&assets==90,"unique titles retain 90 bundled artwork mappings");
        check(all.get(0).rating==9.7&&all.get(89).rating>0&&all.get(90).rating==0,"recommended titles followed by all remaining titles");
        check(all==model.list(0,"",'*',0,none),"same query reuses immutable list snapshot");
        boolean immutable=false;try{all.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"cached snapshots cannot be mutated by callers");
        List<String> pins=PosterCatalog.toggle(none,"正气大侠");List<PosterCatalog.Item> pinned=model.list(0,"",'*',0,pins);
        check(pinned.size()==464&&pinned.get(0).title.equals("正气大侠"),"pin moves low/unrated title to top without dropping titles");
        pins=PosterCatalog.toggle(pins,"猜拳超人");check(pins.isEmpty(),"alias toggles the same canonical pin");
        pins=PosterCatalog.pins("[\"正气大侠\",\"猜拳超人\",\"unknown\",\"龙珠\"]");check(pins.equals(Arrays.asList("正气大侠","龙珠")),"persisted pins normalize aliases and reject duplicates/unknowns");
        check(PosterCatalog.pins("broken").isEmpty(),"corrupt pin preference recovers");
        List<PosterCatalog.Item> filtered=model.list(1,"mztkn",'*',0,pins);check(filtered.size()==1&&filtered.get(0).title.equals("名侦探柯南"),"pins do not escape keyword filters");
        for(PosterCatalog.Item item:model.list(1,"",'M',0,pins))check(AnimeCatalog.identify(item.title).letter()=='M',"letter filter remains authoritative: "+item.title);
        List<PosterCatalog.Item> classic=model.list(3,"",'*',0,pins);check(classic.size()>40&&classic.size()<464,"classic poster wall contains only classic catalog");for(PosterCatalog.Item item:classic)if(!AnimeCatalog.classic(item.title))throw new AssertionError(item.title);
        List<PosterCatalog.Item> ascending=model.list(1,"",'*',1,none),descending=model.list(1,"",'*',2,none);check(ascending.get(0).title.equals(descending.get(descending.size()-1).title),"A-Z and Z-A sorting remain inverse");
        for(int i=0;i<30;i++)model.list(1,"query"+i,'*',0,none);check(model.list(0,"",'*',0,none)!=all,"list snapshot cache evicts old queries instead of growing unbounded");
        System.out.println("PosterCatalogTest: "+checks+" passed");
    }
}
