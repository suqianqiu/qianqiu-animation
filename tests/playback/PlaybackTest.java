package com.qianq.kandongman;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.graphics.*;
import java.io.*;import java.net.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
import org.videolan.libvlc.MediaPlayer;
public final class PlaybackTest extends Instrumentation {
 Activity a;Activity parent;String origin;String remote="",adManifest="",blockedPage="";boolean backControls;StringBuilder log=new StringBuilder();
 Object field(String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
 void set(String n,Object v)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
 void call(final String n,final Class<?>[] types,final Object...args){runOnMainSync(new Runnable(){public void run(){try{Method m=a.getClass().getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(a,args);}catch(Exception e){throw new RuntimeException(e);}}});}
 void check(boolean b,String s)throws Exception{if(!b)throw new Exception(s);log.append("PASS: "+s+"\n");}
 void launch()throws Exception{
  getTargetContext().getSharedPreferences("anime",0).edit().putInt("mode",0).commit();
  if(parent==null)parent=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  Intent i=new Intent(getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  String first=adManifest.isEmpty()?origin+"index.m3u8":adManifest;
  ArrayList<String> names=new ArrayList<String>(Arrays.asList("test 1","test 2")),urls=new ArrayList<String>(Arrays.asList(first,blockedPage.isEmpty()?first:blockedPage));
  i.putExtra("title","Playback verification").putExtra("source",Sources.load(getTargetContext()).get(0).id).putStringArrayListExtra("names",names).putStringArrayListExtra("urls",urls);
  if(!adManifest.isEmpty())i.putExtra("position",348000L);
  a=startActivitySync(i);waitForIdleSync();
 }
 void play(final int mode)throws Exception{runOnMainSync(new Runnable(){public void run(){try{set("mode",mode);}catch(Exception e){throw new RuntimeException(e);}}});call("playMedia",new Class<?>[]{String.class,boolean.class},origin+"index.m3u8",false);}
 void video(String name)throws Exception{
  long end=System.currentTimeMillis()+35000,at=-1;while(System.currentTimeMillis()<end){MediaPlayer p=(MediaPlayer)field("player");if(p!=null&&(Boolean)field("seenVideo")&&p.getTime()>500&&p.isPlaying()){at=p.getTime();break;}Thread.sleep(100);}
  check(at>=500,name+" video output and clock");Thread.sleep(1600);MediaPlayer p=(MediaPlayer)field("player");check(p!=null&&p.getTime()>at+500,name+" clock advances");
  call("showControls",new Class<?>[]{boolean.class},false);Thread.sleep(300);
  Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,name+" screenshot");int bright=0;HashSet<Integer> colors=new HashSet<Integer>();for(int y=b.getHeight()/3;y<b.getHeight()*2/3;y+=13)for(int x=b.getWidth()/3;x<b.getWidth()*2/3;x+=13){int c=b.getPixel(x,y);if(Color.red(c)+Color.green(c)+Color.blue(c)>90)bright++;colors.add(c&0xFFF0F0F0);}check(bright>80&&colors.size()>12,name+" nonblack varying video pixels ("+bright+"/"+colors.size()+")");try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"playback-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
 }
 void close()throws Exception{final Activity old=a;runOnMainSync(new Runnable(){public void run(){old.finish();}});long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end&&(!(Boolean)field("destroyed")||(Integer)field("closingPlayers")!=0||field("vlc")!=null))Thread.sleep(100);check((Boolean)field("destroyed")&&(Integer)field("closingPlayers")==0&&field("vlc")==null,"finish drains players then core");Thread.sleep(1300);a=null;}
 @Override public void onCreate(Bundle b){super.onCreate(b);remote=b==null?"":b.getString("url","");adManifest=b==null?"":b.getString("adManifest","");blockedPage=b==null?"":b.getString("blockedPage","");backControls=b!=null&&"true".equals(b.getString("backControls"));start();}
 boolean controlsVisible()throws Exception{return ((View)field("controls")).getVisibility()==View.VISIBLE;}
 void playbackBack()throws Exception{
  launch();
  video("back-controls");
  MediaPlayer active=(MediaPlayer)field("player");
  sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
  check(controlsVisible(),"Confirm opens the fullscreen controls");
  sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
  check(!controlsVisible()&&!a.isFinishing()&&field("player")==active&&active.isPlaying(),
      "first Back hides controls and keeps the same playback running");
  sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
  check(controlsVisible(),"Menu also opens controls");
  sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
  long deadline=System.currentTimeMillis()+2500;
  while(active.isPlaying()&&System.currentTimeMillis()<deadline)Thread.sleep(80);
  check(!active.isPlaying(),"Pause stops the playback clock");
  sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
  check(!controlsVisible()&&!a.isFinishing()&&field("player")==active&&!active.isPlaying(),
      "first Back also keeps the paused session");
  sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
  waitForIdleSync();
  check(a.isFinishing(),"second Back exits the playback activity");
  close();
 }
 void filteredPlayback()throws Exception {
  launch();
  long deadline=System.currentTimeMillis()+50000;
  long before=-1, after=-1;
  while(System.currentTimeMillis()<deadline) {
   MediaPlayer p=(MediaPlayer)field("player");
   if(p!=null && (Boolean)field("seenVideo") && p.isPlaying()) {
    long time=p.getTime();
    if(before<0 && time>=346000 && time<357000)before=time;
    if(before>=0 && time>=361000) { after=time; break; }
   }
   Thread.sleep(250);
  }
  HlsAdFilter.Result filtered=(HlsAdFilter.Result)field("filteredTimeline");
  check(filtered!=null&&filtered.cuts.length==2,"real HLS prepared a single reviewed removal");
  check(before>=346000,"resume before the removed break at "+before);
  check(after>=361000&&after<378000,"real playback crosses the edited boundary at "+after);
  if(!blockedPage.isEmpty()) {
   call("step",new Class<?>[]{int.class},1);
   Thread.sleep(2500);
   String status=((android.widget.TextView)field("message")).getText().toString();
   check(field("player")==null&&status.contains("广告已压进正片"),"hard-overlay source is blocked before playback");
  }
  close();
 }
 @Override public void onStart(){Bundle result=new Bundle();ServerSocket server=null;ExecutorService pool=Executors.newCachedThreadPool();Map<String,Map<String,?>> saved=new HashMap<String,Map<String,?>>();
  for(String name:new String[]{"anime","watch-history","watch-progress"})saved.put(name,getTargetContext().getSharedPreferences(name,0).getAll());
  try{
   final ServerSocket fixture=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server=fixture;final ExecutorService threads=pool;origin="http://127.0.0.1:"+fixture.getLocalPort()+"/";
   new Thread(new Runnable(){public void run(){while(!fixture.isClosed())try{final Socket s=fixture.accept();threads.execute(new Runnable(){public void run(){try(Socket socket=s){socket.setSoTimeout(3000);BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream()));String first=r.readLine(),line;if(first==null)return;String file=first.split(" ")[1].substring(1);while((line=r.readLine())!=null&&!line.isEmpty()){}if(!file.matches("index.m3u8|seg[0-9]+.ts"))return;ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open(file)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)bytes.write(buf,0,n);}byte[] data=bytes.toByteArray();OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+(file.endsWith("m3u8")?"application/vnd.apple.mpegurl":"video/mp2t")+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);}catch(Exception ignored){}}});}catch(Exception e){break;}}}).start();
   if(backControls){playbackBack();}else if(!adManifest.isEmpty()){filteredPlayback();}else if(!remote.isEmpty()){origin=remote.substring(0,remote.lastIndexOf("/")+1);launch();call("playMedia",new Class<?>[]{String.class,boolean.class},remote,false);video("network");Thread.sleep(5000);video("network-sustained");close();}else{
   launch();video("auto");play(1);video("hardware");play(2);video("software");
   for(int n=0;n<5;n++)play(0);video("rapid-switch");call("step",new Class<?>[]{int.class},1);video("next-episode");
   close();launch();video("reopen");close();}result.putString("stream",log.toString());
  }catch(Throwable e){result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
  finally{if(a!=null){runOnMainSync(new Runnable(){public void run(){a.finish();}});try{Thread.sleep(2000);}catch(Exception ignored){}}if(parent!=null)runOnMainSync(new Runnable(){public void run(){parent.finish();}});if(server!=null)try{server.close();}catch(Exception ignored){}pool.shutdownNow();for(String name:saved.keySet()){SharedPreferences.Editor ed=getTargetContext().getSharedPreferences(name,0).edit().clear();for(Map.Entry<String,?> e:saved.get(name).entrySet()){Object v=e.getValue();String k=e.getKey();if(v instanceof String)ed.putString(k,(String)v);else if(v instanceof Integer)ed.putInt(k,(Integer)v);else if(v instanceof Long)ed.putLong(k,(Long)v);else if(v instanceof Boolean)ed.putBoolean(k,(Boolean)v);else if(v instanceof Float)ed.putFloat(k,(Float)v);else if(v instanceof Set)ed.putStringSet(k,(Set<String>)v);}ed.commit();}}
  finish(result.getString("stream","").contains("ERROR:")?1:-1,result);
 }
}
