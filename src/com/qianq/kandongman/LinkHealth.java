package com.qianq.kandongman;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.InputStream;
import java.util.*;
import org.json.*;

/** Only reproduced 404/410 responses are blacklisted; transient failures are not. */
public final class LinkHealth {
    static final long RETENTION=7L*24*60*60*1000;
    private static final Map<String,Long> blocked=new HashMap<String,Long>();
    private static final Set<String> hidden=new HashSet<String>();
    private static JSONObject searchTerms=new JSONObject();
    private static SharedPreferences prefs;
    private static boolean initialized;
    private static long policyExpires;
    public static synchronized void initialize(Context context) throws Exception {
        if(initialized)return;
        prefs=context.getSharedPreferences("link-health",Context.MODE_PRIVATE);
        try(InputStream in=context.getAssets().open("link-health.json")){
            applyPolicy(new JSONObject(Sources.read(in,3*1024*1024)),System.currentTimeMillis());
        }
        JSONObject saved=new JSONObject(prefs.getString("blocked","{}"));
        Iterator<String> keys=saved.keys();while(keys.hasNext()){String key=keys.next();long expiry=saved.optLong(key);if(expiry>System.currentTimeMillis())blocked.put(key,expiry);}
        initialized=true;
    }
    static synchronized void applyPolicy(JSONObject policy,long now) throws Exception {
        blocked.clear();hidden.clear();
        searchTerms=new JSONObject();
        JSONObject terms=policy.optJSONObject("search_terms");if(terms!=null)searchTerms=terms;
        long expires=policy.optLong("expires_at_ms");policyExpires=expires;if(expires<=now)return;
        JSONArray urls=policy.optJSONArray("blocked_urls"),titles=policy.optJSONArray("hidden_titles");
        if(urls!=null)for(int i=0;i<urls.length();i++)blocked.put(urls.getString(i),expires);
        if(titles!=null)for(int i=0;i<titles.length();i++)hidden.add(titles.getString(i));
    }
    public static synchronized boolean isBlocked(String url){Long until=blocked.get(url);return until!=null&&until>System.currentTimeMillis();}
    public static synchronized boolean hideTitle(String title){return policyExpires>System.currentTimeMillis()&&hidden.contains(title);}
    public static synchronized String searchTerm(String title,String source){JSONObject terms=searchTerms.optJSONObject(title);return terms!=null?terms.optString(source,title):title;}
    public static synchronized void markDead(String url){
        blocked.put(url,System.currentTimeMillis()+RETENTION);
        if(prefs==null)return;
        try{
            JSONObject values=new JSONObject();long now=System.currentTimeMillis();
            Iterator<Map.Entry<String,Long>> entries=blocked.entrySet().iterator();
            while(entries.hasNext()){Map.Entry<String,Long> entry=entries.next();if(entry.getValue()<=now)entries.remove();else values.put(entry.getKey(),entry.getValue());}
            prefs.edit().putString("blocked",values.toString()).apply();
        }catch(Exception ignored){}
    }
}
