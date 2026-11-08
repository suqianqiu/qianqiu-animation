package com.qianq.kandongman;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Visible-window loading, bounded workers, coalesced requests and an isolated thumbnail cache. */
final class PosterLoader {
    static final int MEMORY_LIMIT=12*1024*1024;
    static final long DISK_LIMIT=96L*1024*1024,FRESH_MS=7L*24*60*60*1000,RETRY_MS=60L*60*1000;
    interface Fetcher {String find(Sources.Source source,String title)throws Exception;byte[] image(Sources.Source source,String url)throws Exception;}
    private final Context context;
    private final Fetcher fetcher;
    private final File directory;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(24));
    private final ThreadPoolExecutor localWorkers=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(24));
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(MEMORY_LIMIT){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}};
    private final HashMap<String,Job> pending=new HashMap<String,Job>(); // UI thread only.
    private final HashMap<String,Long> failed=new HashMap<String,Long>();
    private final Object diskLock=new Object();
    private long diskBytes=-1;
    private volatile int epoch;
    private volatile boolean closed;
    final AtomicInteger memoryHits=new AtomicInteger(),diskHits=new AtomicInteger(),networkLoads=new AtomicInteger(),decodes=new AtomicInteger(),coalesced=new AtomicInteger();
    PosterLoader(Context context){this(context,new Fetcher(){public String find(Sources.Source source,String title)throws Exception{return Sources.poster(source,title);}public byte[] image(Sources.Source source,String url)throws Exception{return download(source,url);}});}
    PosterLoader(Context context,Fetcher fetcher){this(context,fetcher,new File(context.getCacheDir(),"poster-thumbnails-v1"));}
    PosterLoader(Context context,Fetcher fetcher,File directory){this.context=context.getApplicationContext();this.fetcher=fetcher;this.directory=directory;}
    static String key(Sources.Source source,String title,String asset,int height){return (asset.isEmpty()?source.id+"|"+AnimeCatalog.canonical(title):"asset|"+asset)+"|"+height;}
    void bind(ImageView image,TextView placeholder,Sources.Source source,String title,String asset,int height){
        String key=key(source,title,asset,height);boolean same=key.equals(image.getTag());image.setTag(key);
        PosterPlaceholder.style(placeholder,title);
        if(!same){image.setImageDrawable(null);placeholder.setText(title+"\n正在加载海报");placeholder.setVisibility(View.VISIBLE);}
        Bitmap cached=memory.get(key);if(cached!=null){memoryHits.incrementAndGet();apply(new Target(image,placeholder),key,cached,title);return;}
        Long retry=failed.get(key);if(retry!=null&&retry>System.currentTimeMillis()){String message=PosterPlaceholder.message(title);if(!message.contentEquals(placeholder.getText()))placeholder.setText(message);placeholder.setVisibility(View.VISIBLE);return;}
        schedule(source,title,asset,height,new Target(image,placeholder));
    }
    void prefetch(Sources.Source source,String title,String asset,int height){String key=key(source,title,asset,height);Long retry=failed.get(key);if(memory.get(key)==null&&(retry==null||retry<=System.currentTimeMillis()))schedule(source,title,asset,height,null);}
    void retain(Set<String> visible){
        for(Iterator<Map.Entry<String,Job>> it=pending.entrySet().iterator();it.hasNext();){Map.Entry<String,Job> entry=it.next();Job job=entry.getValue();if(!visible.contains(entry.getKey())&&!job.running){job.cancelled=true;localWorkers.remove(job);workers.remove(job.remote);it.remove();}}
    }
    private void schedule(Sources.Source source,String title,String asset,int height,Target target){
        if(closed)return;String key=key(source,title,asset,height);Job old=pending.get(key);
        if(old!=null){coalesced.incrementAndGet();if(target!=null)old.watch(target);return;}
        if(pending.size()>=26)return;
        Job job=new Job(source,title,asset,height,key);if(target!=null)job.watch(target);pending.put(key,job);
        try{localWorkers.execute(job);}catch(RejectedExecutionException busy){pending.remove(key);}
    }
    private static final class Target {
        final WeakReference<ImageView> image;final WeakReference<TextView> placeholder;
        Target(ImageView image,TextView placeholder){this.image=new WeakReference<ImageView>(image);this.placeholder=new WeakReference<TextView>(placeholder);}
    }
    private static void apply(Target target,String key,Bitmap bitmap,String title){ImageView image=target.image.get();TextView placeholder=target.placeholder.get();if(image==null||!key.equals(image.getTag()))return;if(bitmap!=null&&(!(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)||((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap()!=bitmap))image.setImageBitmap(bitmap);if(placeholder!=null){if(bitmap==null){String message=PosterPlaceholder.message(title);if(!message.contentEquals(placeholder.getText()))placeholder.setText(message);}int visibility=bitmap==null?View.VISIBLE:View.GONE;if(placeholder.getVisibility()!=visibility)placeholder.setVisibility(visibility);}}
    private final class Job implements Runnable {
        final Sources.Source source;final String title,asset,key;final int height,createdEpoch=epoch;
        final ArrayList<Target> targets=new ArrayList<Target>();volatile boolean running,cancelled;
        Bitmap stale;File image,meta;String name;
        final Runnable remote=new Runnable(){public void run(){running=true;if(!valid())return;try{networkLoads.incrementAndGet();String url=fetcher.find(source,title);if(!valid())return;
            if(url.isEmpty())throw new IOException("来源未提供海报");MediaGuard.check(url);Bitmap bitmap=decode(fetcher.image(source,url),height);if(bitmap==null)throw new IOException("海报不可解码");if(!valid())return;
            synchronized(diskLock){if(!valid())return;if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("海报缓存目录不可用");long before=image.length()+meta.length();File temp=new File(directory,name+".part");try(FileOutputStream out=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,84,out))throw new IOException("海报缓存写入失败");}if(!temp.renameTo(image)){temp.delete();throw new IOException("海报缓存保存失败");}DownloadStore.write(meta,new JSONObject().put("url",url).put("checkedAt",System.currentTimeMillis()).toString());recordWrite(before,image.length()+meta.length());}
            deliver(bitmap,true,false);
        }catch(Exception error){failure();}}};
        Job(Sources.Source source,String title,String asset,int height,String key){this.source=source;this.title=title;this.asset=asset;this.height=height;this.key=key;}
        void watch(Target target){for(Target old:targets)if(old.image.get()==target.image.get())return;targets.add(target);}
        boolean valid(){return !cancelled&&!closed&&createdEpoch==epoch&&!Thread.currentThread().isInterrupted();}
        void deliver(final Bitmap bitmap,final boolean done,final boolean negative){ui.post(new Runnable(){public void run(){if(!valid())return;if(done&&pending.get(key)==Job.this)pending.remove(key);if(negative)failed.put(key,System.currentTimeMillis()+RETRY_MS);if(bitmap!=null)memory.put(key,bitmap);for(Target target:targets)apply(target,key,bitmap,title);}});}
        void failure(){if(!valid())return;if(meta!=null)synchronized(diskLock){try{if(!directory.isDirectory())directory.mkdirs();long before=meta.length();DownloadStore.write(meta,new JSONObject().put("retryAfter",System.currentTimeMillis()+RETRY_MS).toString());recordWrite(before,meta.length());}catch(Exception ignored){}}deliver(stale,true,stale==null);}
        public void run(){
            running=true;if(!valid())return;
            try{
                if(!asset.isEmpty()){try(InputStream in=context.getAssets().open(asset)){Bitmap bitmap=decode(bytes(in,2*1024*1024),height);if(bitmap==null)throw new IOException("海报不可解码");deliver(bitmap,true,false);}return;}
                name=digest(source.id+"|"+AnimeCatalog.canonical(title));image=new File(directory,name+".jpg");meta=new File(directory,name+".json");JSONObject record=new JSONObject();
                synchronized(diskLock){if(meta.isFile())try{record=new JSONObject(DownloadStore.read(meta));}catch(Exception ignored){}if(image.isFile())try(InputStream in=new FileInputStream(image)){stale=decode(bytes(in,2*1024*1024),height);if(stale!=null){diskHits.incrementAndGet();image.setLastModified(System.currentTimeMillis());}}}
                long now=System.currentTimeMillis();boolean fresh=record.optLong("checkedAt")>0&&now>=record.optLong("checkedAt")&&now-record.optLong("checkedAt")<FRESH_MS;
                if((stale!=null&&fresh)||record.optLong("retryAfter")>now){deliver(stale,true,stale==null);return;}
                if(stale!=null)deliver(stale,false,false);
                if(!valid())return;running=false;try{workers.execute(remote);}catch(RejectedExecutionException busy){deliver(stale,true,false);}
            }catch(Exception error){failure();}
        }
    }
    private Bitmap decode(byte[] bytes,int height)throws IOException{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<=0||options.outHeight<=0||options.outWidth>20000||options.outHeight>20000||(long)options.outWidth*options.outHeight>32000000)return null;
        options.inSampleSize=1;int target=Math.min(540,height);while(options.outHeight/(options.inSampleSize*2)>=target)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.RGB_565;decodes.incrementAndGet();return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }
    private static final class DiskEntry {final ArrayList<File> files=new ArrayList<File>();long accessed;}
    private void recordWrite(long before,long after){
        if(diskBytes<0){diskBytes=0;File[] files=directory.listFiles();if(files!=null)for(File file:files)diskBytes+=file.length();}else diskBytes+=after-before;
        if(diskBytes<=DISK_LIMIT)return;File[] files=directory.listFiles();if(files==null)return;HashMap<String,DiskEntry> grouped=new HashMap<String,DiskEntry>();
        for(File file:files){String key=file.getName().split("\\.")[0];DiskEntry entry=grouped.get(key);if(entry==null){entry=new DiskEntry();grouped.put(key,entry);}entry.files.add(file);entry.accessed=Math.max(entry.accessed,file.lastModified());}
        ArrayList<DiskEntry> entries=new ArrayList<DiskEntry>(grouped.values());Collections.sort(entries,new Comparator<DiskEntry>(){public int compare(DiskEntry a,DiskEntry b){return Long.compare(a.accessed,b.accessed);}});
        for(DiskEntry entry:entries){if(diskBytes<=DISK_LIMIT)break;for(File file:entry.files){long size=file.length();if(file.delete())diskBytes-=size;}}
    }
    String stats(){return String.format(Locale.CHINA,"海报内存 %.1f / 12 MB；本地缓存上限 96 MB。清理仅影响海报，保留置顶、观看记录和视频下载。",memory.size()/1048576.0);}
    void clear(final Runnable done){epoch++;for(Job job:pending.values())job.cancelled=true;pending.clear();workers.getQueue().clear();localWorkers.getQueue().clear();memory.evictAll();failed.clear();localWorkers.execute(new Runnable(){public void run(){synchronized(diskLock){File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();diskBytes=-1;}ui.post(new Runnable(){public void run(){if(!closed)done.run();}});}});}
    void close(){closed=true;epoch++;for(Job job:pending.values())job.cancelled=true;pending.clear();workers.shutdownNow();localWorkers.shutdownNow();ui.removeCallbacksAndMessages(null);memory.evictAll();}
    private static String digest(String text)throws Exception{byte[] data=MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte value:data)out.append(String.format(Locale.US,"%02x",value&255));return out.toString();}
    private static byte[] bytes(InputStream in,int limit)throws IOException{long deadline=System.currentTimeMillis()+10000;ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted()||System.currentTimeMillis()>deadline||out.size()+n>limit)throw new IOException("海报请求取消、超时或超过大小限制");out.write(buffer,0,n);}return out.toByteArray();}
    private static byte[] download(Sources.Source source,String address)throws Exception{
        long deadline=System.currentTimeMillis()+10000;
        for(int hop=0;hop<5;hop++){
            MediaGuard.check(address);if(!Sources.http(address)||System.currentTimeMillis()>deadline)throw new IOException("海报地址不可用");HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
            if(c instanceof javax.net.ssl.HttpsURLConnection&&CompatTls.factory()!=null)((javax.net.ssl.HttpsURLConnection)c).setSSLSocketFactory(CompatTls.factory());
            c.setConnectTimeout(3500);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent",source.agent());c.setRequestProperty("Referer",source.base());
            try{int code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){address=Sources.absolute(address,c.getHeaderField("Location"));continue;}if(code!=200)throw new IOException("海报HTTP "+code);String type=c.getContentType();if(type!=null&&!type.toLowerCase(Locale.US).startsWith("image/"))throw new IOException("不是海报图片");try(InputStream in=c.getInputStream()){return bytes(in,2*1024*1024);}}finally{c.disconnect();}
        }throw new IOException("海报跳转过多");
    }
}
