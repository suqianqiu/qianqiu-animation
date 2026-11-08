package com.qianq.kandongman;

import java.util.*;
import org.json.*;

/** Immutable list snapshots. Pins never discard titles or escape an active filter. */
final class PosterCatalog {
    static final class Item {
        final String title,caption,asset,initials;
        final double rating;
        Item(AnimeCatalog.Entry entry,JSONObject popular){
            title=entry.title;initials=entry.initials;asset=popular==null?"":popular.optString("poster");
            rating=popular==null?0:popular.optDouble("rating",0);
            caption=rating>0?String.format(Locale.CHINA,"%.1f 分 · %s",rating,popular.optString("title",title)):title;
        }
    }
    private final ArrayList<AnimeCatalog.Entry> catalog=AnimeCatalog.load();
    private final Map<String,Item> items=new HashMap<String,Item>();
    private final LinkedHashMap<String,List<Item>> snapshots=new LinkedHashMap<String,List<Item>>(24,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,List<Item>> entry){return size()>24;}
    };
    PosterCatalog(JSONArray popular){
        Map<String,JSONObject> rated=new HashMap<String,JSONObject>();
        for(int i=0;i<popular.length();i++){JSONObject row=popular.optJSONObject(i);if(row!=null)rated.put(AnimeCatalog.canonical(row.optString("catalogTitle")),row);}
        for(AnimeCatalog.Entry e:catalog)items.put(e.title,new Item(e,rated.get(e.title)));
    }
    synchronized List<Item> list(int scope,String query,char letter,final int sort,List<String> pins){
        String cacheKey=scope+"|"+sort+"|"+letter+"|"+AnimeCatalog.normalize(query)+"|"+new JSONArray(pins);
        List<Item> cached=snapshots.get(cacheKey);if(cached!=null)return cached;
        final HashMap<String,Integer> ranks=new HashMap<String,Integer>();for(int i=0;i<pins.size();i++)if(!ranks.containsKey(pins.get(i)))ranks.put(pins.get(i),i);
        final boolean ratedFirst=scope==0&&sort==0;
        ArrayList<Item> result=new ArrayList<Item>();
        for(AnimeCatalog.Entry entry:AnimeCatalog.filter(catalog,query,letter))if(scope!=3||AnimeCatalog.classic(entry.title))result.add(items.get(entry.title));
        Collections.sort(result,new Comparator<Item>(){public int compare(Item a,Item b){
            Integer pa=ranks.get(a.title),pb=ranks.get(b.title);if(pa!=null||pb!=null){if(pa==null)return 1;if(pb==null)return -1;return pa.compareTo(pb);}
            if(ratedFirst){int n=Double.compare(b.rating,a.rating);if(n!=0)return n;}
            int n=a.initials.compareTo(b.initials);if(n==0)n=a.title.compareTo(b.title);return sort==2?-n:n;
        }});
        List<Item> immutable=Collections.unmodifiableList(result);snapshots.put(cacheKey,immutable);return immutable;
    }
    static List<String> pins(String json){
        LinkedHashSet<String> titles=new LinkedHashSet<String>();
        try{JSONArray a=new JSONArray(json);for(int i=0;i<a.length();i++){AnimeCatalog.Entry e=AnimeCatalog.identify(a.optString(i));if(e!=null)titles.add(e.title);}}catch(Exception ignored){}
        return new ArrayList<String>(titles);
    }
    static List<String> toggle(List<String> pins,String title){ArrayList<String> out=new ArrayList<String>(pins);String canonical=AnimeCatalog.canonical(title);if(!out.remove(canonical)&&AnimeCatalog.identify(canonical)!=null)out.add(0,canonical);return out;}
}
