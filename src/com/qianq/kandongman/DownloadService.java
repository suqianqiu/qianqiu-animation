package com.qianq.kandongman;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only cache transport; player and downloader share each segment's lock. */
public final class DownloadService extends Service {
    static volatile String base="";
    private ServerSocket server;
    private final ExecutorService clients=new ThreadPoolExecutor(2,6,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16));
    private final Handler ui=new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private DownloadStore store;
    private final Runnable update=new Runnable(){public void run(){
        int active=0;String text="本地缓存已就绪";
        for(DownloadStore.Task t:store.list())if(t.running&&!t.paused){active++;text=t.title+" · "+t.episode+"  "+t.done()+"/"+t.resources.size();}
        if(active>0&&!wake.isHeld())wake.acquire(10*60*1000L);else if(active==0&&wake.isHeld())wake.release();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(130,notification(text));ui.postDelayed(this,3000);
    }};
    static void start(Context c){Intent i=new Intent(c,DownloadService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    static String url(DownloadStore.Task t)throws Exception{for(int i=0;i<100&&base.isEmpty();i++)Thread.sleep(50);if(base.isEmpty())throw new IOException("本地缓存服务未启动");return base+t.id+"/index.m3u8";}
    @Override public void onCreate(){super.onCreate();store=DownloadStore.get(this);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("downloads","动漫后台缓存",NotificationManager.IMPORTANCE_LOW));
        startForeground(130,notification("正在准备本地缓存"));wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"qianqiu:downloads");
        try{server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));final String token=UUID.randomUUID().toString();base="http://127.0.0.1:"+server.getLocalPort()+"/"+token+"/";
            new Thread(new Runnable(){public void run(){while(!server.isClosed())try{final Socket socket=server.accept();try{clients.execute(new Runnable(){public void run(){serve(socket,token);}});}catch(RejectedExecutionException e){socket.close();}}catch(IOException e){break;}}},"anime-cache-http").start();
        }catch(Exception e){android.util.Log.e("AnimeCache","Loopback startup",e);stopSelf();return;}
        ui.post(update);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){store.resumeAll();return START_NOT_STICKY;}
    private Notification notification(String text){Intent i=new Intent(this,DownloadsActivity.class);PendingIntent p=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"downloads"):new Notification.Builder(this);return b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("千秋动画 · 后台缓存").setContentText(text).setContentIntent(p).setOngoing(true).build();}
    private void serve(Socket socket,String token){try(Socket s=socket){s.setSoTimeout(20000);BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream(),"US-ASCII"));String first=reader.readLine();if(first==null)return;String[] request=first.split(" ");String range="";for(int n=0;n<60;n++){String line=reader.readLine();if(line==null||line.isEmpty())break;if(line.toLowerCase(Locale.US).startsWith("range:"))range=line.substring(6).trim();}
        OutputStream out=s.getOutputStream();if(request.length<2||(!request[0].equals("GET")&&!request[0].equals("HEAD"))){reply(out,400,"Bad request");return;}
        String[] path=request[1].split("/");if(path.length!=4||!path[1].equals(token)||!path[2].matches("[a-f0-9]{64}")){reply(out,404,"Missing");return;}
        DownloadStore.Task t=store.find(path[2]);if(t==null||t.deleted){reply(out,404,"Missing");return;}
        File f;String type="application/octet-stream";
        try{if(path[3].equals("index.m3u8")&&t.hls){f=new File(t.directory,"index.m3u8");type="application/vnd.apple.mpegurl";}else if(path[3].matches("r[0-9]+")){int index=Integer.parseInt(path[3].substring(1));if(index>=t.resources.size()){reply(out,404,"Missing");return;}f=store.resource(t,index);}else{reply(out,404,"Missing");return;}}
        catch(Exception e){reply(out,503,"Resource unavailable");return;}
        long length=f.length(),start=0,end=length-1;boolean partial=false;
        if(!range.isEmpty()){try{if(!range.matches("bytes=[0-9]+-[0-9]*"))throw new IOException();String[] r=range.substring(6).split("-",-1);start=Long.parseLong(r[0]);if(!r[1].isEmpty())end=Math.min(end,Long.parseLong(r[1]));if(start>end||start>=length)throw new IOException();partial=true;}catch(Exception e){out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+length+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));return;}}
        String header="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+type+"\r\nAccept-Ranges: bytes\r\nContent-Length: "+(end-start+1)+"\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+length+"\r\n":"")+"Connection: close\r\n\r\n";out.write(header.getBytes("US-ASCII"));
        if(!request[0].equals("HEAD"))try(RandomAccessFile file=new RandomAccessFile(f,"r")){file.seek(start);byte[] b=new byte[65536];long left=end-start+1;while(left>0){int n=file.read(b,0,(int)Math.min(b.length,left));if(n<0)break;out.write(b,0,n);left-=n;}}out.flush();
    }catch(Exception ignored){}}
    private void reply(OutputStream out,int code,String message)throws IOException{out.write(("HTTP/1.1 "+code+" "+message+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){base="";ui.removeCallbacksAndMessages(null);if(wake!=null&&wake.isHeld())wake.release();try{if(server!=null)server.close();}catch(Exception ignored){}clients.shutdownNow();super.onDestroy();}
}
