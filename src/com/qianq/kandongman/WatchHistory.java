package com.qianq.kandongman;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** Full episode lists are written only on episode changes; progress is separate. */
final class WatchHistory {
    static final int MAX_ITEMS=20,MAX_CHARS=2*1024*1024;
    private static String key(JSONObject h){return h.optString("source")+"|"+h.optString("title");}
    private static SharedPreferences records(Context c){return c.getSharedPreferences("watch-history",Context.MODE_PRIVATE);}
    private static SharedPreferences progress(Context c){return c.getSharedPreferences("watch-progress",Context.MODE_PRIVATE);}
    static JSONArray upsert(JSONArray old,JSONObject entry) throws Exception {
        JSONArray out=new JSONArray();out.put(entry);int size=entry.toString().length();
        for(int i=0;i<old.length()&&out.length()<MAX_ITEMS;i++){
            JSONObject item=old.getJSONObject(i);if(key(item).equals(key(entry)))continue;
            int n=item.toString().length();if(size+n>MAX_CHARS)continue;out.put(item);size+=n;
        }return out;
    }
    static synchronized void save(Context c,JSONObject snapshot,int index,long position) throws Exception {
        JSONObject entry=new JSONObject(snapshot.toString());entry.put("index",index);entry.put("position",position);entry.put("updatedAt",System.currentTimeMillis());
        JSONArray old=new JSONArray(records(c).getString("items","[]"));
        records(c).edit().putString("items",upsert(old,entry).toString()).apply();update(c,entry.optString("source"),entry.optString("title"),index,position);
    }
    static void update(Context c,String source,String title,int index,long position){
        String k=source+"|"+title;progress(c).edit().putInt(k+"/index",index).putLong(k+"/position",Math.max(0,position)).putLong(k+"/at",System.currentTimeMillis()).apply();
    }
    static synchronized ArrayList<JSONObject> list(Context c) throws Exception {
        JSONArray values=new JSONArray(records(c).getString("items","[]"));
        if(values.length()==0){
            SharedPreferences legacy=c.getSharedPreferences("anime",Context.MODE_PRIVATE);
            JSONObject item=new JSONObject(legacy.getString("history","{}"));
            if(item.has("names")&&item.has("urls")&&!item.optString("title").isEmpty()){
                save(c,item,legacy.getInt("lastIndex",0),legacy.getLong("lastPosition",0));values=new JSONArray(records(c).getString("items","[]"));
            }
        }
        ArrayList<JSONObject> out=new ArrayList<JSONObject>();SharedPreferences p=progress(c);
        for(int i=0;i<values.length();i++){
            JSONObject item=values.getJSONObject(i);String k=key(item);
            item.put("index",p.getInt(k+"/index",item.optInt("index")));item.put("position",p.getLong(k+"/position",item.optLong("position")));item.put("updatedAt",p.getLong(k+"/at",item.optLong("updatedAt")));
            if(item.optJSONArray("names")!=null&&item.optJSONArray("urls")!=null&&item.getJSONArray("names").length()==item.getJSONArray("urls").length()&&item.getJSONArray("urls").length()>0)out.add(item);
        }
        Collections.sort(out,new Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){return Long.compare(b.optLong("updatedAt"),a.optLong("updatedAt"));}});return out;
    }
}
