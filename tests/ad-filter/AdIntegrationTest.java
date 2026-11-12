package com.qianq.kandongman;
import android.app.*;import android.content.*;import android.os.*;import android.graphics.*;
import java.io.*;import java.net.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
import org.videolan.libvlc.MediaPlayer;

/** Exercises the shipped Android download/filter/player paths with original reviewed TS bytes. */
public final class AdIntegrationTest extends Instrumentation {
    private int checks;private Bundle options;private StringBuilder log=new StringBuilder();private Activity player;
    private Context context;private DownloadStore store;private DownloadStore.Task downloaded;
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void require(boolean value,String text){if(!value)throw new AssertionError(text);checks++;log.append("PASS ").append(text).append('\n');}
    private byte[] asset(String name)throws Exception{try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private long removed(HlsAdFilter.Result r){long n=0;for(int i=0;i<r.cuts.length;i+=2)n+=r.cuts[i+1]-r.cuts[i];return n;}
    private void erase(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)erase(c);f.delete();}
    @Override public void onCreate(Bundle args){options=args==null?new Bundle():args;super.onCreate(args);start();}
    @Override public void onStart(){context=getTargetContext();Bundle result=new Bundle();ServerSocket server=null;ExecutorService requests=Executors.newCachedThreadPool();File rawDirectory=null;
        Map<String,Map<String,?>> saved=new HashMap<>();for(String n:new String[]{"anime","watch-history","watch-progress"})saved.put(n,context.getSharedPreferences(n,0).getAll());
        try{
            store=DownloadStore.get(context);final ServerSocket fixture=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));server=fixture;final ExecutorService workers=requests;
            String base="http://127.0.0.1:"+fixture.getLocalPort()+"/";
            Thread accept=new Thread(()->{while(!fixture.isClosed())try{final Socket connection=fixture.accept();workers.execute(()->{try(Socket s=connection){s.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));String line=in.readLine();if(line==null)return;String name=line.split(" ")[1].substring(1);while((line=in.readLine())!=null&&!line.isEmpty()){}if(!name.matches("index.m3u8|seg[0-7].ts"))return;byte[] data=asset(name);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\nContent-Type: "+(name.endsWith("m3u8")?"application/vnd.apple.mpegurl":"video/mp2t")+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);}catch(Exception ignored){}});}catch(IOException closed){break;}});accept.setDaemon(true);accept.start();
            HlsAdFilter.Result online=store.inspectAds(base+"index.m3u8",base+"index.m3u8",""," ");
            require(online!=null&&Arrays.equals(online.cuts,new long[]{2920,20560}),"production network probe handles NONE and renamed six-piece advertisement");
            require(online.playlist.contains("seg0.ts")&&online.playlist.contains("seg7.ts")&&!online.playlist.contains("seg1.ts"),"online result retains both plot sides");
            String rawText=new String(asset("index.m3u8"),"UTF-8");HlsCache raw=HlsCache.parse(base+"index.m3u8",u->rawText);
            DownloadStore.Task legacy=new DownloadStore.Task();legacy.page=base+"index.m3u8";legacy.address=legacy.page;legacy.hls=true;legacy.playlist=raw.playlist;legacy.resources.addAll(raw.urls);legacy.agent="";legacy.referer="";legacy.directory=new File(context.getCacheDir(),"ad-legacy-"+UUID.randomUUID());legacy.directory.mkdirs();rawDirectory=legacy.directory;
            for(int i=0;i<8;i++)try(OutputStream out=new FileOutputStream(new File(legacy.directory,"r"+i))){out.write(asset("seg"+i+".ts"));}
            require(legacy.complete(),"old unfiltered cache fixture is complete");
            HlsAdFilter.Result cached=store.inspectCachedAds(legacy,new File(legacy.entry()).toURI().toString(),true);
            require(cached!=null&&Arrays.equals(cached.cuts,online.cuts),"old cache gets the same verified removal as online playback");
            require(cached.playlist.contains("/r0")&&cached.playlist.contains("/r7")&&!cached.playlist.contains("/r1"),"cached resource indices retain plot and exclude advertisement");
            Sources.Source source=Sources.load(context).get(0);
            downloaded=store.prepare(source,base+"index.m3u8","Ad verification","clip",base+"index.m3u8");
            require(downloaded.resources.size()==2&&Arrays.equals(downloaded.adCuts,online.cuts),"new download queues only retained plot with original timeline mapping");
            store.enqueue(downloaded);long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end&&!downloaded.complete())SystemClock.sleep(50);
            require(downloaded.complete(),"filtered download completes");
            for(int i=0;i<2;i++){byte[] expected=asset(i==0?"seg0.ts":"seg7.ts");byte[] actual;try(InputStream in=new FileInputStream(new File(downloaded.directory,"r"+i));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);actual=out.toByteArray();}require(Arrays.equals(expected,actual),"downloaded plot bytes unchanged "+i);}
            HlsAdFilter.Result rechecked=store.inspectCachedAds(downloaded,new File(downloaded.entry()).toURI().toString(),true);
            require(rechecked!=null&&Arrays.equals(rechecked.cuts,online.cuts),"already filtered cache keeps earlier cut mapping after rescan");
            Intent intent=new Intent(context,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title","Ad playback verification").putExtra("source",source.id).putStringArrayListExtra("names",new ArrayList<String>(Arrays.asList("clip"))).putStringArrayListExtra("urls",new ArrayList<String>(Arrays.asList(base+"index.m3u8")));
            player=startActivitySync(intent);long before=-1,after=-1;end=SystemClock.elapsedRealtime()+30000;
            while(SystemClock.elapsedRealtime()<end){MediaPlayer mp=(MediaPlayer)field(player,"player");if(mp!=null&&(Boolean)field(player,"seenVideo")&&mp.isPlaying()){long t=mp.getTime();if(before<0&&t>=300&&t<2920)before=t;if(t>=4500){after=t;break;}}SystemClock.sleep(50);}
            require(before>=300&&after>=4500,"production VLC playback crosses the edited plot seam");
            HlsAdFilter.Result timeline=(HlsAdFilter.Result)field(player,"filteredTimeline");require(timeline!=null&&Arrays.equals(timeline.cuts,online.cuts),"player uses verified removal and timeline mapping");
            Bitmap shot=getUiAutomation().takeScreenshot();require(shot!=null,"post-seam screenshot captured");try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"ad-filter-seam.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
            Activity old=player;runOnMainSync(old::finish);end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end&&(!(Boolean)field(player,"destroyed")||(Integer)field(player,"closingPlayers")!=0||field(player,"vlc")!=null))SystemClock.sleep(100);require((Boolean)field(player,"destroyed")&&(Integer)field(player,"closingPlayers")==0&&field(player,"vlc")==null,"player and native core release after filtered playback");player=null;
            String remote=options.getString("remote","");if(!remote.isEmpty()){long started=SystemClock.elapsedRealtime();HlsAdFilter.Result real=store.inspectAds(remote,remote,"Mozilla/5.0",remote);require(real!=null&&removed(real)==Long.parseLong(options.getString("removedMs")),"real remote media passes bounded production probes ("+(SystemClock.elapsedRealtime()-started)+" ms)");}
            result.putString("result","PASS");
        }catch(Throwable e){result.putString("result","FAIL");log.append(android.util.Log.getStackTraceString(e));}
        finally{if(player!=null){Activity old=player;runOnMainSync(old::finish);SystemClock.sleep(1500);}if(downloaded!=null){store.delete(downloaded);long cleanup=SystemClock.elapsedRealtime()+4000;while(downloaded.directory.exists()&&SystemClock.elapsedRealtime()<cleanup)SystemClock.sleep(30);}if(rawDirectory!=null)erase(rawDirectory);if(server!=null)try{server.close();}catch(Exception ignored){}requests.shutdownNow();for(String n:saved.keySet()){SharedPreferences.Editor ed=context.getSharedPreferences(n,0).edit().clear();for(Map.Entry<String,?> item:saved.get(n).entrySet()){Object v=item.getValue();String k=item.getKey();if(v instanceof String)ed.putString(k,(String)v);else if(v instanceof Integer)ed.putInt(k,(Integer)v);else if(v instanceof Long)ed.putLong(k,(Long)v);else if(v instanceof Boolean)ed.putBoolean(k,(Boolean)v);else if(v instanceof Float)ed.putFloat(k,(Float)v);else if(v instanceof Set)ed.putStringSet(k,(Set<String>)v);}ed.commit();}}
        result.putInt("checks",checks);result.putString("details",log.toString());finish("PASS".equals(result.getString("result"))?-1:0,result);
    }
}
