package com.qianq.kandongman;
import org.json.*;
public final class WatchHistoryTest {
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static JSONObject item(String title,int index,long position)throws Exception{return new JSONObject().put("title",title).put("source","MXdm").put("names",new JSONArray().put("第1集").put("第2集")).put("urls",new JSONArray().put("https://example.test/1").put("https://example.test/2")).put("index",index).put("position",position);}
    public static void main(String[] args)throws Exception{
        JSONArray all=new JSONArray();for(int i=0;i<25;i++)all=WatchHistory.upsert(all,item("show"+i,1,i*1000));
        check(all.length()==20,"History limit");check(all.getJSONObject(0).getString("title").equals("show24"),"Newest first");
        all=WatchHistory.upsert(all,item("show10",1,123456));check(all.length()==20,"Deduplication");
        check(all.getJSONObject(0).getString("title").equals("show10")&&all.getJSONObject(0).getLong("position")==123456,"Resume position lost");
        check(all.getJSONObject(0).getJSONArray("urls").length()==2,"Full episode list lost");
        JSONObject large=item("large",0,0).put("padding",new String(new char[WatchHistory.MAX_CHARS]).replace('\0','a'));
        all=WatchHistory.upsert(all,large);check(all.length()==1,"Old entries must respect size budget");
        System.out.println("PASS: newest first, 20-title limit, deduplication, episode/position preservation, storage budget");
    }
}
