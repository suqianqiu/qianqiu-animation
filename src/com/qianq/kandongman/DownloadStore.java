package com.qianq.kandongman;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Persistent, bounded disk cache. Final files are published only after a complete response. */
final class DownloadStore {
    static final class Task {
        String id,title,episode,page,source,address,agent,referer,state="等待下载",error="",playlist="";
        volatile boolean paused,deleted,running;
        boolean hls,background,manual;
        volatile long bytes,speed,inflight;
        long lastBytes,lastTick;
        final ArrayList<String> resources=new ArrayList<String>();
        final ArrayList<Object> locks=new ArrayList<Object>();
        File directory;
        synchronized JSONObject json()throws Exception{
            JSONObject j=new JSONObject();j.put("id",id).put("title",title).put("episode",episode).put("page",page).put("source",source).put("address",address).put("agent",agent).put("referer",referer).put("state",state).put("error",error).put("paused",paused).put("manual",manual).put("background",background).put("hls",hls).put("playlist",playlist).put("resources",new JSONArray(resources));return j;
        }
        int done(){int n=0;for(int i=0;i<resources.size();i++)if(new File(directory,"r"+i).isFile())n++;return n;}
        boolean complete(){return !resources.isEmpty()&&done()==resources.size();}
        String entry(){return new File(directory,hls?"index.m3u8":"r0").getAbsolutePath();}
    }
    private static DownloadStore instance;
    static synchronized DownloadStore get(Context c){if(instance==null)instance=new DownloadStore(c.getApplicationContext());return instance;}
    final Context context;
    private final File root;
    private final LinkedHashMap<String,Task> tasks=new LinkedHashMap<String,Task>();
    private final ExecutorService queue=Executors.newSingleThreadExecutor();
    private DownloadStore(Context c){context=c;root=new File(c.getFilesDir(),"downloads");root.mkdirs();try{CompatTls.initialize(c);}catch(Exception ignored){}
        File[] dirs=root.listFiles();if(dirs!=null)for(File d:dirs)try{
            JSONObject j=new JSONObject(read(new File(d,"task.json")));Task t=new Task();t.directory=d;t.id=j.getString("id");t.title=j.getString("title");t.episode=j.getString("episode");t.page=j.getString("page");t.source=j.getString("source");t.address=j.getString("address");t.agent=j.getString("agent");t.referer=j.getString("referer");t.hls=j.getBoolean("hls");t.background=j.optBoolean("background",false);t.manual=j.optBoolean("manual",false);t.playlist=j.optString("playlist");t.paused=j.optBoolean("paused");JSONArray a=j.getJSONArray("resources");for(int i=0;i<a.length();i++){t.resources.add(a.getString(i));t.locks.add(new Object());}if(!t.manual&&!t.complete())t.paused=true;t.bytes=size(d);t.state=t.complete()?"已完成":t.paused?"已暂停":"等待继续";tasks.put(t.id,t);
        }catch(Exception ignored){}
    }
    static String id(String source,String page)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest((source+"\n"+page).getBytes("UTF-8"));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.US,"%02x",v&255));return s.toString();}
    synchronized ArrayList<Task> list(){return new ArrayList<Task>(tasks.values());}
    synchronized Task find(String id){return tasks.get(id);}
    Task find(String source,String page){try{return find(id(source,page));}catch(Exception e){return null;}}
    private synchronized void save(Task t)throws Exception{if(!t.deleted)write(new File(t.directory,"task.json"),t.json().toString());}
    Task prepare(final Sources.Source source,final String page,String title,String episode,final String address)throws Exception{
        String key=id(source.id,page);Task old=find(key);if(old!=null&&!old.resources.isEmpty())return old;
        Task t=new Task();t.id=key;t.title=title;t.episode=episode;t.source=source.id;t.page=page;t.address=address;t.agent=source.agent();t.referer=source.rule.optString("referer").isEmpty()?page:source.rule.optString("referer");t.directory=new File(root,key);
        t.hls=address.toLowerCase(Locale.US).contains(".m3u8");
        if(t.hls){HlsCache parsed=HlsCache.parse(address,new HlsCache.Fetch(){public String get(String url)throws Exception{return fetchText(url,source.agent(),source.rule.optString("referer").isEmpty()?page:source.rule.optString("referer"));}});t.resources.addAll(parsed.urls);t.playlist=parsed.playlist;}
        else {if(!address.toLowerCase(Locale.US).matches(".*\\.(mp4|m4v|webm)([?#].*)?"))throw new IOException("此格式暂不支持本地缓存");t.resources.add(address);}
        for(int i=0;i<t.resources.size();i++)t.locks.add(new Object());
        synchronized(this){old=tasks.get(key);if(old!=null&&!old.resources.isEmpty())return old;if(old!=null){old.address=t.address;old.agent=t.agent;old.referer=t.referer;old.hls=t.hls;old.playlist=t.playlist;old.resources.addAll(t.resources);old.locks.addAll(t.locks);t=old;}t.directory.mkdirs();if(t.hls)write(new File(t.directory,"index.m3u8"),t.playlist);tasks.put(key,t);save(t);}return t;
    }
    Task enqueueEpisode(Sources.Source source,String page,String title,String episode)throws Exception{
        Task t; synchronized(this){t=find(source.id,page);if(t==null){t=new Task();t.id=id(source.id,page);t.title=title;t.episode=episode;t.page=page;t.source=source.id;t.address="";t.agent=source.agent();t.referer=source.rule.optString("referer").isEmpty()?page:source.rule.optString("referer");t.directory=new File(root,t.id);t.directory.mkdirs();tasks.put(t.id,t);save(t);}}
        enqueue(t);return t;
    }
    synchronized void resumeAll(){for(Task t:list())if(t.manual&&t.background&&!t.paused&&!t.complete())enqueue(t);}
    synchronized void enqueue(final Task t){if(t.deleted||t.complete())return;if(t.running){if(t.paused){t.paused=false;queue.execute(new Runnable(){public void run(){enqueue(t);}});}return;}t.paused=false;t.manual=true;t.background=true;t.running=true;t.error="";t.state="等待下载";try{save(t);}catch(Exception ignored){}queue.execute(new Runnable(){public void run(){
        try{if(t.resources.isEmpty()){t.state="正在解析播放地址";Sources.Source chosen=null;for(Sources.Source s:Sources.load(context))if(s.id.equals(t.source))chosen=s;if(chosen==null)throw new IOException("来源不存在");String resolved=Sources.resolve(chosen,t.page);if(resolved==null)throw new IOException("此线路需先进入播放页面解析，再重试下载");prepare(chosen,t.page,t.title,t.episode,resolved);}t.state="下载中";for(int i=0;i<t.resources.size();i++){if(t.paused||t.deleted)break;resource(t,i);}if(!t.paused&&!t.deleted){t.state="已完成";t.speed=0;}}
        catch(Exception e){if(!t.paused&&!t.deleted){t.state="下载失败";t.error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}}
        finally{t.running=false;if(t.paused)t.state="已暂停";try{save(t);}catch(Exception ignored){}}
    }});}
    void pause(Task t){t.paused=true;t.state="已暂停";t.speed=0;try{save(t);}catch(Exception ignored){}}
    void delete(Task t){t.deleted=true;t.paused=true;synchronized(this){tasks.remove(t.id);}queue.execute(new Runnable(){public void run(){remove(t.directory);}});}
    synchronized long used(){return size(root);}
    long limit(){return context.getSharedPreferences("anime",0).getInt("cacheGB",4)*1024L*1024*1024;}
    void room()throws IOException{if(root.getUsableSpace()<512L*1024*1024)throw new IOException("剩余空间不足 512 MB，请清理下载");if(used()>=limit())throw new IOException("缓存已达上限，请清理下载或提高上限");}
    File resource(Task t,int index)throws Exception{
        synchronized(t.locks.get(index)){
            File output=new File(t.directory,"r"+index);if(output.isFile())return output;
            if(t.deleted)throw new IOException("任务已删除");if(t.paused)throw new IOException("任务已暂停，请继续下载");
            Exception failure=null;
            for(int attempt=0;attempt<3;attempt++){
                HttpURLConnection c=null;File partial=new File(t.directory,"r"+index+".part");
                try{
                    room();c=open(t.resources.get(index),t.agent,t.referer);long expected=-1;try{expected=Long.parseLong(c.getHeaderField("Content-Length"));}catch(Exception ignored){}if(expected>0&&(expected>limit()-used()||expected>root.getUsableSpace()-512L*1024*1024))throw new IOException("此资源超过剩余缓存空间");
                    long got=0,check=0;try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(partial)){
                        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(t.paused||t.deleted)throw new IOException("任务已暂停");out.write(b,0,n);got+=n;t.inflight=got;if(got-check>2*1024*1024){room();check=got;}long now=System.currentTimeMillis();if(now-t.lastTick>=1000){t.speed=Math.max(0,(t.bytes+got-t.lastBytes)*1000/Math.max(1,now-t.lastTick));t.lastBytes=t.bytes+got;t.lastTick=now;}}
                    }
                    if(expected>=0&&got!=expected)throw new IOException("下载不完整，将重试");if(got==0)throw new IOException("空的视频资源");
                    if(t.deleted||t.paused)throw new IOException("任务已暂停");if(!partial.renameTo(output))throw new IOException("缓存写入失败");synchronized(t){t.bytes+=got;t.inflight=0;}return output;
                }catch(Exception e){failure=e;t.inflight=0;partial.delete();if(t.paused||t.deleted)break;Thread.sleep(500L*(attempt+1));}
                finally{if(c!=null)c.disconnect();}
            }
            throw failure;
        }
    }
    static HttpURLConnection open(String url,String agent,String referer)throws Exception{
        for(int redirects=0;redirects<6;redirects++){
            MediaGuard.check(url);if(!Sources.http(url))throw new IOException("仅支持 HTTP/HTTPS");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();if(c instanceof javax.net.ssl.HttpsURLConnection&&CompatTls.factory()!=null)((javax.net.ssl.HttpsURLConnection)c).setSSLSocketFactory(CompatTls.factory());
            c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent",agent);c.setRequestProperty("Referer",referer);c.setRequestProperty("Accept-Encoding","identity");
            try{int code=c.getResponseCode();if(code>=300&&code<400){String next=HlsCache.absolute(url,c.getHeaderField("Location"));c.disconnect();url=next;continue;}if(code!=200)throw new IOException("HTTP "+code+"，可重试或换源");return c;}catch(Exception e){c.disconnect();throw e;}
        }throw new IOException("重定向过多");
    }
    static String fetchText(String url,String ua,String ref)throws Exception{HttpURLConnection c=open(url,ua,ref);try(InputStream in=c.getInputStream()){return Sources.read(in,3*1024*1024);}finally{c.disconnect();}}
    static String read(File f)throws Exception{try(InputStream in=new FileInputStream(f)){return Sources.read(in,4*1024*1024);}}
    static void write(File f,String text)throws Exception{File temp=new File(f.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes("UTF-8"));out.getFD().sync();}if(!temp.renameTo(f))throw new IOException("保存失败");}
    static long size(File f){if(f.isFile())return f.length();long n=0;File[] a=f.listFiles();if(a!=null)for(File v:a)n+=size(v);return n;}
    private static void remove(File f){File[] a=f.listFiles();if(a!=null)for(File v:a)remove(v);f.delete();}
    static String mb(long n){return String.format(Locale.US,"%.1f MB",n/1048576.0);}
}
