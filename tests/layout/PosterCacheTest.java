package com.qianq.kandongman;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Isolated thumbnail fixtures: no real source requests and no changes to the user's cache. */
final class PosterCacheTest {
    private static void check(boolean ok,String label,StringBuilder report){if(!ok)throw new AssertionError(label);report.append("PASS: ").append(label).append('\n');}
    private static void drain(Instrumentation test,PosterLoader loader)throws Exception{
        long until=SystemClock.elapsedRealtime()+10000;final int[] count={1};Field field=PosterLoader.class.getDeclaredField("pending");field.setAccessible(true);
        while(SystemClock.elapsedRealtime()<until){test.waitForIdleSync();test.runOnMainSync(()->{try{count[0]=((Map)field.get(loader)).size();}catch(Exception e){throw new RuntimeException(e);}});if(count[0]==0)return;SystemClock.sleep(50);}throw new AssertionError("poster request completion timeout");
    }
    static String run(Instrumentation test,Context context)throws Exception{
        StringBuilder report=new StringBuilder();File directory=new File(context.getCacheDir(),"poster-fixture-"+System.nanoTime());ArrayList<PosterLoader> loaders=new ArrayList<PosterLoader>();
        try{
            final AtomicInteger calls=new AtomicInteger();final AtomicBoolean background=new AtomicBoolean(true);final Sources.Source source=new Sources.Source("fixture","fixture",new JSONObject().put("baseURL","https://example.org"));
            Bitmap red=Bitmap.createBitmap(24,36,Bitmap.Config.RGB_565);red.eraseColor(Color.RED);ByteArrayOutputStream redBytes=new ByteArrayOutputStream();red.compress(Bitmap.CompressFormat.PNG,100,redBytes);red.recycle();
            Bitmap blue=Bitmap.createBitmap(24,36,Bitmap.Config.RGB_565);blue.eraseColor(Color.BLUE);ByteArrayOutputStream blueBytes=new ByteArrayOutputStream();blue.compress(Bitmap.CompressFormat.PNG,100,blueBytes);blue.recycle();
            PosterLoader.Fetcher fetcher=new PosterLoader.Fetcher(){public String find(Sources.Source s,String title)throws Exception{calls.incrementAndGet();background.compareAndSet(true,Looper.myLooper()!=Looper.getMainLooper());Thread.sleep(title.equals("进击的巨人")?350:100);return title.equals("正气大侠")?"":"https://example.org/"+(title.equals("海贼王")?"blue":"red")+".png";}public byte[] image(Sources.Source s,String url){return url.contains("blue")?blueBytes.toByteArray():redBytes.toByteArray();}};
            final PosterLoader[] holder=new PosterLoader[1];final ImageView[] images=new ImageView[2];final TextView[] placeholders=new TextView[2];
            test.runOnMainSync(()->{holder[0]=new PosterLoader(context,fetcher,directory);for(int i=0;i<2;i++){images[i]=new ImageView(context);placeholders[i]=new TextView(context);}holder[0].bind(images[0],placeholders[0],source,"火影忍者","",160);holder[0].bind(images[1],placeholders[1],source,"火影忍者","",160);});PosterLoader loader=holder[0];loaders.add(loader);drain(test,loader);
            check(calls.get()==1&&loader.coalesced.get()>0,"duplicate visible requests share one network job",report);check(background.get(),"poster fetching runs off the UI thread",report);
            final boolean[] same={false};test.runOnMainSync(()->{same[0]=images[0].getDrawable()!=null&&images[1].getDrawable()!=null;loader.bind(images[0],placeholders[0],source,"火影忍者","",160);});check(same[0]&&calls.get()==1&&loader.memoryHits.get()>0,"memory cache avoids repeat requests",report);
            test.runOnMainSync(()->{loader.bind(images[0],placeholders[0],source,"进击的巨人","",160);loader.bind(images[0],placeholders[0],source,"海贼王","",160);});drain(test,loader);
            final int[] color={0};test.runOnMainSync(()->{color[0]=((BitmapDrawable)images[0].getDrawable()).getBitmap().getPixel(0,0);});check(Color.blue(color[0])>200&&Color.red(color[0])<50,"recycled image ignores a late response for an older title",report);
            test.runOnMainSync(()->{loader.bind(images[1],placeholders[1],source,"正气大侠","",160);});drain(test,loader);int afterFailure=calls.get();test.runOnMainSync(()->{loader.bind(images[1],placeholders[1],source,"正气大侠","",160);});check(calls.get()==afterFailure,"missing artwork has a negative cache instead of retrying each bind",report);
            final int[] placeholderColor={0};final boolean[] decorated={false};test.runOnMainSync(()->{placeholderColor[0]=((android.graphics.drawable.ColorDrawable)placeholders[1].getBackground()).getColor();decorated[0]=placeholders[1].getVisibility()==android.view.View.VISIBLE&&placeholders[1].getText().toString().contains("故事，从这里开始")&&placeholders[1].getText().toString().contains("正气大侠");});check(decorated[0],"missing poster shows opaque Morandi block, title and copy",report);
            test.runOnMainSync(()->loader.bind(images[1],placeholders[1],source,"正气大侠","",160));check(((android.graphics.drawable.ColorDrawable)placeholders[1].getBackground()).getColor()==placeholderColor[0],"negative-cache rebind keeps placeholder color stable",report);
            java.util.HashSet<Integer> palette=new java.util.HashSet<Integer>();for(AnimeCatalog.Entry entry:AnimeCatalog.load())palette.add(PosterPlaceholder.color(entry.title));check(palette.size()==8,"catalog distributes missing posters across all eight muted colors",report);
            test.runOnMainSync(()->loader.close());final AtomicInteger offlineRequests=new AtomicInteger();
            PosterLoader.Fetcher offline=new PosterLoader.Fetcher(){public String find(Sources.Source s,String title)throws Exception{offlineRequests.incrementAndGet();throw new IOException("offline");}public byte[] image(Sources.Source s,String url)throws Exception{throw new IOException("offline");}};
            test.runOnMainSync(()->{holder[0]=new PosterLoader(context,offline,directory);holder[0].bind(images[0],placeholders[0],source,"火影忍者","",160);holder[0].bind(images[1],placeholders[1],source,"正气大侠","",160);});PosterLoader recovered=holder[0];loaders.add(recovered);drain(test,recovered);
            check(recovered.diskHits.get()==1&&offlineRequests.get()==0,"disk artwork and negative metadata survive loader restart offline",report);
            CountDownLatch cleared=new CountDownLatch(1);test.runOnMainSync(()->recovered.clear(()->cleared.countDown()));check(cleared.await(5,TimeUnit.SECONDS),"thumbnail cache clears asynchronously",report);
            check(directory.listFiles()!=null&&directory.listFiles().length==0,"clear removes only the isolated thumbnail files",report);
            File oldImage=new File(directory,"old.jpg"),oldMeta=new File(directory,"old.json"),newImage=new File(directory,"new.jpg"),newMeta=new File(directory,"new.json");
            try(RandomAccessFile file=new RandomAccessFile(oldImage,"rw")){file.setLength(64L*1024*1024);}try(RandomAccessFile file=new RandomAccessFile(newImage,"rw")){file.setLength(40L*1024*1024);}try(FileOutputStream file=new FileOutputStream(oldMeta)){file.write(1);}try(FileOutputStream file=new FileOutputStream(newMeta)){file.write(1);}
            long now=System.currentTimeMillis();oldImage.setLastModified(now-100000);oldMeta.setLastModified(now-100000);newImage.setLastModified(now);newMeta.setLastModified(now);
            java.lang.reflect.Method record=PosterLoader.class.getDeclaredMethod("recordWrite",long.class,long.class);record.setAccessible(true);record.invoke(recovered,0L,0L);
            check(!oldImage.exists()&&!oldMeta.exists()&&newImage.exists()&&newMeta.exists(),"96MB disk limit evicts oldest artwork and metadata together",report);
            // Block the two workers briefly, then overfill the queue and prune it.
            final PosterLoader[] bounded=new PosterLoader[1];CountDownLatch release=new CountDownLatch(1);PosterLoader.Fetcher delayed=new PosterLoader.Fetcher(){public String find(Sources.Source s,String title)throws Exception{release.await(2,TimeUnit.SECONDS);return "";}public byte[] image(Sources.Source s,String url){return redBytes.toByteArray();}};
            test.runOnMainSync(()->{bounded[0]=new PosterLoader(context,delayed,new File(directory,"bounded"));int count=0;for(AnimeCatalog.Entry entry:AnimeCatalog.load()){if(count++==100)break;bounded[0].prefetch(source,entry.title,"",160);}});loaders.add(bounded[0]);
            Field workers=PosterLoader.class.getDeclaredField("workers");workers.setAccessible(true);ThreadPoolExecutor pool=(ThreadPoolExecutor)workers.get(bounded[0]);check(pool.getQueue().size()<=24&&pool.getMaximumPoolSize()==2,"rapid scrolling has at most two workers and 24 queued jobs",report);
            test.runOnMainSync(()->bounded[0].retain(Collections.<String>emptySet()));check(pool.getQueue().isEmpty(),"offscreen queued requests are cancelled",report);release.countDown();
            // A fresh local asset must not wait behind a source network request.
            CountDownLatch localRelease=new CountDownLatch(1);PosterLoader.Fetcher blocked=new PosterLoader.Fetcher(){public String find(Sources.Source s,String title)throws Exception{localRelease.await(3,TimeUnit.SECONDS);return "";}public byte[] image(Sources.Source s,String url){return redBytes.toByteArray();}};
            final PosterLoader[] local=new PosterLoader[1];test.runOnMainSync(()->{local[0]=new PosterLoader(context,blocked,new File(directory,"local-priority"));local[0].prefetch(source,"火影忍者","",160);local[0].prefetch(source,"海贼王","",160);});loaders.add(local[0]);SystemClock.sleep(100);
            test.runOnMainSync(()->local[0].bind(images[0],placeholders[0],source,"排球少年","posters/26285156.jpg",160));final boolean[] loaded={false};long localDeadline=SystemClock.elapsedRealtime()+1000;while(!loaded[0]&&SystemClock.elapsedRealtime()<localDeadline){test.runOnMainSync(()->loaded[0]=images[0].getDrawable()!=null);SystemClock.sleep(20);}
            check(loaded[0]&&localRelease.getCount()==1,"local posters load while both network workers are blocked",report);localRelease.countDown();
            return report.toString();
        }finally{test.runOnMainSync(()->{for(PosterLoader loader:loaders)loader.close();});for(String name:new String[]{"bounded","local-priority"}){File nested=new File(directory,name);File[] children=nested.listFiles();if(children!=null)for(File f:children)f.delete();nested.delete();}File[] children2=directory.listFiles();if(children2!=null)for(File f:children2)f.delete();directory.delete();}
    }
}
