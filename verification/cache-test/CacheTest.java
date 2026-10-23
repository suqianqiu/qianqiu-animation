package com.qianq.kandongman;
import android.app.*;import android.os.*;import android.content.*;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;

public final class CacheTest extends Instrumentation {
    private final ArrayList<DownloadStore.Task> restoreTasks=new ArrayList<DownloadStore.Task>();
    private void restore(){for(DownloadStore.Task task:restoreTasks){task.paused=false;try{DownloadStore.get(getTargetContext()).enqueue(task);}catch(Exception ignored){}}}
    private final StringBuilder log=new StringBuilder();
    private void check(boolean yes,String name)throws Exception{if(!yes)throw new Exception("FAIL: "+name);log.append("PASS: ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();ServerSocket server=null;DownloadStore.Task t=null;try{
        final Context ctx=getTargetContext();final java.util.concurrent.atomic.AtomicInteger hits=new java.util.concurrent.atomic.AtomicInteger();
        final ServerSocket fixture=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server=fixture;
        final ExecutorService pool=Executors.newCachedThreadPool();
        new Thread(new Runnable(){public void run(){while(!fixture.isClosed())try{final Socket socket=fixture.accept();pool.execute(new Runnable(){public void run(){try(Socket s=socket){BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));String first=r.readLine();String file=first.split(" ")[1].substring(1);String line;while((line=r.readLine())!=null&&!line.isEmpty()){}if(!file.matches("index.m3u8|seg[0-9]+.ts"))return;hits.incrementAndGet();byte[] data;try(InputStream in=getContext().getAssets().open(file);ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);data=b.toByteArray();}OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);}catch(Exception ignored){}}});}catch(Exception e){break;}}}).start();
        final String origin="http://127.0.0.1:"+fixture.getLocalPort()+"/";
        HlsCache parsed=HlsCache.parse(origin+"master",new HlsCache.Fetch(){public String get(String u){return u.endsWith("master")?"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nsub/index.m3u8":"#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST";}});
        check(parsed.urls.size()==3&&parsed.urls.get(0).equals(origin+"key")&&parsed.playlist.contains("URI=\"r0\""),"master, relative URLs, AES key and fMP4 map rewritten");
        boolean rejected=false;try{HlsCache.parse(origin,new HlsCache.Fetch(){public String get(String u){return "#EXTM3U\n#EXTINF:2,\na.ts";}});}catch(IOException expected){rejected=true;}check(rejected,"live playlist rejected");
        rejected=false;try{HlsCache.parse(origin,new HlsCache.Fetch(){public String get(String u){return "#EXTM3U\n#EXT-X-BYTERANGE:10@0\na.ts\n#EXT-X-ENDLIST";}});}catch(IOException expected){rejected=true;}check(rejected,"unsupported byte-range playlist rejected before caching");
        JSONArray cleaned=new JSONArray();for(JSONObject h:WatchHistory.list(ctx))if(!h.optString("title").equals("缓存验证（18 秒测试片）"))cleaned.put(h);
        ctx.getSharedPreferences("watch-history",0).edit().putString("items",cleaned.toString()).commit();
        if(cleaned.length()>0){JSONObject h=cleaned.getJSONObject(0);ctx.getSharedPreferences("anime",0).edit().putString("history",h.toString()).putInt("lastIndex",h.optInt("index")).putLong("lastPosition",h.optLong("position")).commit();}
        else ctx.getSharedPreferences("anime",0).edit().remove("history").remove("lastIndex").remove("lastPosition").commit();
        DownloadStore store=DownloadStore.get(ctx);for(DownloadStore.Task existing:store.list())if(!existing.title.equals("缓存验证（18 秒测试片）")&&!existing.paused&&!existing.complete()){restoreTasks.add(existing);store.pause(existing);}
        for(DownloadStore.Task old:store.list())if(old.title.equals("缓存验证（18 秒测试片）")){store.delete(old);long until=System.currentTimeMillis()+10000;while(old.directory.exists()&&System.currentTimeMillis()<until)Thread.sleep(50);}
        Sources.Source source=new Sources.Source("MXdm","Fixture",new JSONObject());
        t=store.prepare(source,origin+"testpage","缓存验证（18 秒测试片）","第1集",origin+"index.m3u8");
        check(t.resources.size()==9,"real HLS fixture parsed");
        store.resource(t,0);int before=hits.get();store.resource(t,0);check(hits.get()==before,"cached segment reused without a second upstream request");
        store.pause(t);boolean paused=false;try{store.resource(t,1);}catch(IOException expected){paused=true;}check(paused&&t.done()==1,"pause blocks uncached requests and preserves complete segments");
        DownloadService.start(ctx);store.enqueue(t);long deadline=System.currentTimeMillis()+60000;while(!t.complete()&&System.currentTimeMillis()<deadline)Thread.sleep(100);check(t.complete(),"foreground service downloads all remaining segments");
        String local=DownloadService.url(t);HttpURLConnection c=(HttpURLConnection)new URL(local).openConnection();check(c.getResponseCode()==200,"loopback rewritten playlist is served");c.disconnect();
        c=(HttpURLConnection)new URL(local.replace("index.m3u8","r0")).openConnection();c.setRequestProperty("Range","bytes=0-15");check(c.getResponseCode()==206&&c.getContentLength()==16,"loopback Range requests supported");c.disconnect();
        DownloadStore.Task queued=store.enqueueEpisode(source,origin+"index.m3u8","缓存验证（18 秒测试片）","排队下载集");
        check(store.enqueueEpisode(source,origin+"index.m3u8","缓存验证（18 秒测试片）","排队下载集")==queued,"hierarchical download queue deduplicates episode URLs");
        deadline=System.currentTimeMillis()+60000;while(!queued.complete()&&!queued.state.equals("下载失败")&&System.currentTimeMillis()<deadline)Thread.sleep(100);check(queued.complete(),"queued episode resolves and downloads without opening player: "+queued.state+" "+queued.error);store.delete(queued);
        fixture.close();pool.shutdownNow();int offlineHits=hits.get();store.resource(t,1);check(hits.get()==offlineHits,"cached segment remains readable with upstream offline");
        java.lang.reflect.Field instance=DownloadStore.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);DownloadStore restored=DownloadStore.get(ctx);DownloadStore.Task recovered=restored.find(t.id);check(recovered!=null&&recovered.complete(),"persisted task and cached files recover after store reload");
        Intent intent=new Intent(ctx,PlayerActivity.class);intent.putExtra("cacheId",t.id);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);final Activity activity=startActivitySync(intent);
        java.lang.reflect.Field playerField=PlayerActivity.class.getDeclaredField("player"),seen=PlayerActivity.class.getDeclaredField("seenVideo");playerField.setAccessible(true);seen.setAccessible(true);boolean played=false;deadline=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<deadline){Object p2=playerField.get(activity);if(p2!=null&&((org.videolan.libvlc.MediaPlayer)p2).getTime()>2000&&seen.getBoolean(activity)){played=true;break;}Thread.sleep(300);}
        check(played,"LibVLC decoded cached HLS offline with video output and advancing time");runOnMainSync(new Runnable(){public void run(){activity.finish();}});
        int savedGB=ctx.getSharedPreferences("anime",0).getInt("cacheGB",4);ctx.getSharedPreferences("anime",0).edit().putInt("cacheGB",0).commit();boolean bounded=false;try{store.room();}catch(IOException expected){bounded=true;}ctx.getSharedPreferences("anime",0).edit().putInt("cacheGB",savedGB).commit();check(bounded,"cache capacity limit enforced");
        final Activity manager=startActivitySync(new Intent(ctx,DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field filterField=DownloadsActivity.class.getDeclaredField("filter"),tasksField=DownloadsActivity.class.getDeclaredField("tasks");filterField.setAccessible(true);tasksField.setAccessible(true);filterField.setInt(manager,2);final java.lang.reflect.Method render=DownloadsActivity.class.getDeclaredMethod("render");render.setAccessible(true);runOnMainSync(new Runnable(){public void run(){try{render.invoke(manager);}catch(Exception e){throw new RuntimeException(e);}}});
        boolean fixtureVisible=false;for(DownloadStore.Task row:(ArrayList<DownloadStore.Task>)tasksField.get(manager)){check(row.complete(),"downloaded-files filter excludes unfinished tasks");if(row.id.equals(t.id))fixtureVisible=true;}check(fixtureVisible,"completed offline file appears in file management");runOnMainSync(new Runnable(){public void run(){manager.finish();}});
        store.delete(t);deadline=System.currentTimeMillis()+10000;while(t.directory.exists()&&System.currentTimeMillis()<deadline)Thread.sleep(100);check(!t.directory.exists(),"delete removes task and local resources");
        final Activity home=startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final java.lang.reflect.Method episodesMethod=MainActivity.class.getDeclaredMethod("episodes",Sources.Source.class,Sources.Item.class,Sources.Road.class);episodesMethod.setAccessible(true);final ArrayList<Sources.Item> episodeRows=new ArrayList<Sources.Item>();episodeRows.add(new Sources.Item("第1集",origin+"fixture-page"));final Sources.Source fixtureSource=source;
        runOnMainSync(new Runnable(){public void run(){try{episodesMethod.invoke(home,fixtureSource,new Sources.Item("测试国语版",origin),new Sources.Road("测试线路",episodeRows));}catch(Exception e){throw new RuntimeException(e);}}});waitForIdleSync();
        java.lang.reflect.Field lv=MainActivity.class.getDeclaredField("list");lv.setAccessible(true);final android.widget.GridView grid=(android.widget.GridView)lv.get(home);runOnMainSync(new Runnable(){public void run(){grid.requestFocus();grid.setSelection(0);}});waitForIdleSync();
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(400);
        java.lang.reflect.Field dialogField=MainActivity.class.getDeclaredField("downloadDialog");dialogField.setAccessible(true);AlertDialog downloadDialog=(AlertDialog)dialogField.get(home);check(downloadDialog!=null&&downloadDialog.isShowing()&&downloadDialog.getButton(-1).getText().toString().equals("下载"),"episode row Right then Confirm opens download dialog instead of player");sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);runOnMainSync(new Runnable(){public void run(){home.finish();}});
        restore();result.putString("stream",log.toString());finish(-1,result);
    }catch(Exception e){restore();result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));finish(1,result);return;}}
}
