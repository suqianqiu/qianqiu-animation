package com.qianq.kandongman;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IVLCVout;
import java.util.*;
import java.util.concurrent.*;

public final class PlayerActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ArrayList<String> names,urls;
    private String title,mediaUrl="",resolvedUrl="";
    private String browserCandidate="";private int browserCandidateCount;
    private Sources.Source source;
    private LibVLC vlc;
    private MediaPlayer player;
    private WebView browser;
    private SharedPreferences prefs;
    private FrameLayout root;
    private SurfaceView surface;
    private View cover;
    private LinearLayout controls;
    private TextView message,time;
    private Button pause;
    private String cacheOnly="";
    private DownloadStore.Task cacheTask;
    private SeekBar seek;
    private int index,mode,generation;
    private long resumeAt,lastSave;
    private boolean foreground,resolving,softRetry,seenVideo,seeking;
    private final String[] modes={"自动：优先硬解，失败后重试软解","仅硬解","仅软解"};
    private final Runnable tick=new Runnable(){public void run(){
        if(player!=null&&foreground){
            long at=player.getTime(),length=player.getLength();
            time.setText(clock(at)+" / "+(length>0?clock(length):"--:--"));
            if(!seeking&&length>0)seek.setProgress((int)Math.min(1000,at*1000/length));
            if(System.currentTimeMillis()-lastSave>10000)savePosition();
        }
        ui.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().setFlags(1024,1024);getWindow().addFlags(128);
        prefs=getSharedPreferences("anime",MODE_PRIVATE);mode=Math.max(0,Math.min(2,prefs.getInt("mode",0)));
        cacheOnly=getIntent().getStringExtra("cacheId");
        if(cacheOnly!=null){
            DownloadStore.Task cached=DownloadStore.get(this).find(cacheOnly);
            if(cached==null){finish();return;}
            getIntent().putExtra("title",cached.title);getIntent().putExtra("source",cached.source);
            ArrayList<String> cn=new ArrayList<String>(),cu=new ArrayList<String>();cn.add(cached.episode);cu.add(cached.page);
            getIntent().putStringArrayListExtra("names",cn);getIntent().putStringArrayListExtra("urls",cu);
        }
        names=getIntent().getStringArrayListExtra("names");urls=getIntent().getStringArrayListExtra("urls");title=getIntent().getStringExtra("title");
        if(names==null||urls==null||urls.isEmpty()||names.size()!=urls.size()){finish();return;}
        index=Math.max(0,Math.min(urls.size()-1,getIntent().getIntExtra("index",0)));resumeAt=Math.max(0,getIntent().getLongExtra("position",0));if(cacheOnly!=null)resumeAt=prefs.getLong("cachePosition/"+cacheOnly,0);
        try {for(Sources.Source s:Sources.load(this))if(s.id.equals(getIntent().getStringExtra("source")))source=s;}catch(Exception ignored){}
        if(source==null){finish();return;}
        buildUi();
        try {ArrayList<String> opts=new ArrayList<String>();opts.add("--network-caching=10000");opts.add("--no-video-title-show");opts.add("--aout=opensles_android");opts.add("--gnutls-dir-trust="+CompatTls.directory());vlc=new LibVLC(this,opts);}
        catch(Throwable error){status("播放器初始化失败："+error.getClass().getSimpleName());}
        saveHistory();ui.post(tick);
    }
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(int size){TextView t=new TextView(this);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    private void buildUi() {
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
        surface=new SurfaceView(this);surface.setFocusable(false);root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        surface.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){public void onLayoutChange(View v,int a,int b,int c,int d,int e,int f,int g,int h){if(player!=null)player.getVLCVout().setWindowSize(c-a,d-b);}});
        cover=new View(this);cover.setBackgroundColor(Color.BLACK);root.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        message=label(19);message.setPadding(dp(20),dp(12),dp(20),dp(12));message.setBackgroundColor(0xD91B1C32);message.setMaxLines(4);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);root.addView(message,mp);
        controls=new LinearLayout(this);controls.setOrientation(1);controls.setPadding(dp(16),dp(10),dp(16),dp(10));controls.setBackgroundColor(0xEF17182A);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        time=label(16);controls.addView(time);seek=new SeekBar(this);seek.setMax(1000);controls.addView(seek,new LinearLayout.LayoutParams(-1,dp(38)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seeking=true;}public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&player!=null&&player.getLength()>0){time.setText(clock(player.getLength()*p/1000)+" / "+clock(player.getLength()));if(!seeking)player.setTime(player.getLength()*p/1000);}}public void onStopTrackingTouch(SeekBar s){seeking=false;if(player!=null&&player.getLength()>0)player.setTime(player.getLength()*s.getProgress()/1000);}});
        LinearLayout row=new LinearLayout(this);controls.addView(row);
        button(row,"上一集",new Runnable(){public void run(){step(-1);}});
        pause=button(row,"暂停",new Runnable(){public void run(){if(player!=null){if(player.isPlaying()){player.pause();pause.setText("继续");}else{player.play();pause.setText("暂停");}}}});
        button(row,"下一集",new Runnable(){public void run(){step(1);}});
        button(row,"选集",new Runnable(){public void run(){selectEpisode();}});
        button(row,"解码",new Runnable(){public void run(){decoder();}});
        button(row,"重试",new Runnable(){public void run(){resumeAt=player==null?resumeAt:Math.max(0,player.getTime());loadEpisode();}});
        button(row,"缓存下载",new Runnable(){public void run(){requestDownload();}});
        button(row,"下载管理",new Runnable(){public void run(){startActivity(new Intent(PlayerActivity.this,DownloadsActivity.class));}});
        button(row,"收起",new Runnable(){public void run(){showControls(false);}});
        pause.requestFocus();
    }
    private Button button(LinearLayout row,String title,final Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(16);TvStyle.button(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),0,dp(3),0);row.addView(b,lp);return b;}
    private String clock(long millis){long secs=Math.max(0,millis/1000);return String.format(java.util.Locale.US,"%02d:%02d",secs/60,secs%60);}
    private void status(String text){if(message!=null)message.setText(title+" · "+names.get(index)+"\n"+text);}
    private void showControls(boolean show){controls.setVisibility(show?View.VISIBLE:View.GONE);message.setVisibility(show?View.VISIBLE:View.GONE);if(show)pause.requestFocus();else{root.setFocusableInTouchMode(true);root.requestFocus();}}
    private void clearBrowser(){if(browser!=null){root.removeView(browser);browser.stopLoading();browser.loadUrl("about:blank");browser.destroy();browser=null;}}
    private void clearPlayer(){cover.setVisibility(View.VISIBLE);if(player!=null){player.setEventListener(null);player.stop();player.getVLCVout().detachViews();player.release();player=null;}}
    private void loadEpisode() {
        if(!foreground||vlc==null)return;
        final int token=++generation;clearBrowser();clearPlayer();resolving=true;mediaUrl="";resolvedUrl="";browserCandidate="";browserCandidateCount=0;seek.setProgress(0);status("正在解析 · "+source.name);showControls(true);saveHistory();
        final String page=urls.get(index);
        final DownloadStore.Task existing=DownloadStore.get(this).find(source.id,page);
        if(existing!=null&&(existing.complete()||cacheOnly!=null)){
            if(!existing.complete()&&existing.paused){resolving=false;status("缓存已暂停，请到下载管理继续");return;}
            resolving=false;beginMedia(existing.address,false);return;
        }
        worker.execute(new Runnable(){public void run(){
            try {final String found=Sources.resolve(source,page);ui.post(new Runnable(){public void run(){if(!active(token))return;if(found!=null){resolving=false;beginMedia(found,false);}else resolveInBrowser(page,token);}});}
            catch(final Exception error){ui.post(new Runnable(){public void run(){if(!active(token))return;resolving=false;status("解析失败："+error.getMessage()+"；返回换线路或点击重试。");}});}
        }});
        ui.postDelayed(new Runnable(){public void run(){if(active(token)&&resolving){generation++;resolving=false;clearBrowser();status("解析超时，请返回选择其他线路或来源。");showControls(true);}}},45000);
    }
    private boolean active(int token){return foreground&&!isDestroyed()&&token==generation;}
    @SuppressWarnings("deprecation") private void resolveInBrowser(final String page,final int token) {
        status("安全解析中 · 已限制网页跳转、弹窗与广告请求");
        browser=new WebView(this);WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setBlockNetworkImage(true);settings.setMediaPlaybackRequiresUserGesture(true);settings.setUserAgentString(source.agent());
        browser.setDownloadListener(new DownloadListener(){public void onDownloadStart(String a,String b,String c,String d,long e){}});
        browser.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView v,boolean d,boolean g,Message m){return false;}
            @Override public boolean onJsAlert(WebView v,String u,String m,JsResult r){r.cancel();return true;}
            @Override public boolean onJsConfirm(WebView v,String u,String m,JsResult r){r.cancel();return true;}
            @Override public void onPermissionRequest(PermissionRequest r){r.deny();}
        });
        root.addView(browser,new FrameLayout.LayoutParams(1,1,Gravity.BOTTOM|Gravity.RIGHT));browser.setFocusable(false);
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !MediaGuard.sameDocument(page,url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return MediaGuard.blocked(request.getUrl().toString())||(request.isForMainFrame()&&!MediaGuard.sameDocument(page,request.getUrl().toString()));}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){String url=request.getUrl().toString();if(MediaGuard.blocked(url)||Sources.media(url))return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));return null;}
        });browser.loadUrl(page);pollDeclaredVideo(page,token);
    }
    private void pollDeclaredVideo(final String page,final int token){
        ui.postDelayed(new Runnable(){public void run(){if(!active(token)||!resolving||browser==null)return;
            String script="(function(){var out={configured:[],videos:[]};Object.keys(window).filter(function(k){return /^player_[a-zA-Z0-9_]+$/.test(k);}).forEach(function(k){var p=window[k];if(p&&p.url)out.configured.push({url:p.url,encrypt:p.encrypt||0});});document.querySelectorAll('video').forEach(function(v){var u=v.currentSrc||v.getAttribute('src');if(u)out.videos.push(new URL(u,document.baseURI).href);});return JSON.stringify(out);})()";
            browser.evaluateJavascript(script,new ValueCallback<String>(){public void onReceiveValue(String value){if(!active(token)||!resolving)return;String selected="";boolean configured=false;
                try{Object decoded=new JSONTokener(value).nextValue();JSONObject object=new JSONObject(String.valueOf(decoded));JSONArray declared=object.getJSONArray("configured");for(int i=0;i<declared.length();i++){JSONObject config=declared.getJSONObject(i);String found=Sources.extractMedia("player_guard="+config.toString());if(found!=null&&!MediaGuard.blocked(found)){selected=found;configured=true;break;}}
                    if(selected.isEmpty()){JSONArray videos=object.getJSONArray("videos");for(int i=0;i<videos.length();i++){String found=videos.getString(i);if(Sources.media(found)&&!MediaGuard.blocked(found)){selected=found;break;}}}
                }catch(Exception ignored){}
                if(!selected.isEmpty()){if(selected.equals(browserCandidate))browserCandidateCount++;else{browserCandidate=selected;browserCandidateCount=1;}
                    if(configured||browserCandidateCount>=3){final String candidate=selected;resolving=false;clearBrowser();worker.execute(new Runnable(){public void run(){try{final String checked=Sources.checkMedia(source,candidate,page);ui.post(new Runnable(){public void run(){if(active(token))beginMedia(checked,false);}});}catch(final Exception e){ui.post(new Runnable(){public void run(){if(active(token))status(e.getMessage());}});}}});return;}
                }pollDeclaredVideo(page,token);
            }});
        }},750);
    }
    private void beginMedia(final String address,final boolean software) {
        if(!foreground||vlc==null)return;
        if(software||address.startsWith("file:")||(!DownloadService.base.isEmpty()&&address.startsWith(DownloadService.base))){playMedia(address,software);return;}
        try{MediaGuard.check(address);}catch(Exception e){status(e.getMessage());return;}
        resolvedUrl=address;
        final DownloadStore.Task existing=DownloadStore.get(this).find(source.id,urls.get(index));
        if(existing!=null&&existing.complete()){cacheTask=existing;playMedia(Uri.fromFile(new java.io.File(existing.entry())).toString(),software);return;}
        if(cacheOnly!=null&&existing!=null&&existing.hls){
            if(existing.paused){status("缓存已暂停，请到下载管理继续");return;}
            DownloadService.start(this);final int token=++generation;worker.execute(new Runnable(){public void run(){try{final String local=DownloadService.url(existing);ui.post(new Runnable(){public void run(){if(active(token))playMedia(local,software);}});}catch(final Exception e){ui.post(new Runnable(){public void run(){if(active(token))status(e.getMessage());}});}}});return;
        }
        // Ordinary playback uses only the player's memory buffer; never creates a disk task.
        playMedia(address,software);
    }
    private void requestDownload(){
        final Sources.Source chosen=source;final String page=urls.get(index),name=names.get(index),series=title,address=resolvedUrl;
        final DownloadStore store=DownloadStore.get(this);DownloadStore.Task old=store.find(chosen.id,page);
        if(old!=null&&old.complete()){Toast.makeText(this,"本集已下载，可在下载管理离线播放",Toast.LENGTH_LONG).show();return;}
        DownloadService.start(this);Toast.makeText(this,"已请求缓存下载；可在下载管理查看",Toast.LENGTH_SHORT).show();
        worker.execute(new Runnable(){public void run(){try{DownloadStore.Task task=address.isEmpty()?store.enqueueEpisode(chosen,page,series,name):store.prepare(chosen,page,series,name,address);store.enqueue(task);}catch(final Exception e){ui.post(new Runnable(){public void run(){if(!isDestroyed())Toast.makeText(PlayerActivity.this,"下载失败："+e.getMessage(),Toast.LENGTH_LONG).show();}});}}});
    }
    private void playMedia(final String address,final boolean software) {
        if(!foreground||vlc==null)return;
        if(LinkHealth.isBlocked(address)){status("视频地址已失效（404/410），请返回换线路");return;}
        final int token=++generation;clearPlayer();mediaUrl=address;softRetry=software;seenVideo=false;
        status("正在连接 · "+((mode==2||software)?"软解":mode==1?"硬解":"优先硬解"));pause.setText("暂停");
        android.util.Log.i("KanDongman","start mode="+mode+" software="+software);
        try {
            player=new MediaPlayer(vlc);IVLCVout out=player.getVLCVout();out.setVideoView(surface);out.setWindowSize(surface.getWidth(),surface.getHeight());
            out.attachViews(new IVLCVout.OnNewVideoLayoutListener(){public void onNewVideoLayout(IVLCVout v,int w,int h,int vw,int vh,int sn,int sd){
                if(!active(token)||w<=0||h<=0)return;seenVideo=true;cover.setVisibility(View.GONE);
                double ratio=(double)(vw>0?vw:w)/(vh>0?vh:h);if(sn>0&&sd>0)ratio*=((double)sn/sd);
                int rw=root.getWidth(),rh=root.getHeight(),nw=rw,nh=(int)(rw/ratio);if(nh>rh){nh=rh;nw=(int)(rh*ratio);}surface.setLayoutParams(new FrameLayout.LayoutParams(nw,nh,Gravity.CENTER));
                android.util.Log.i("KanDongman","video-layout "+w+"x"+h+" software="+software);
            }});
            player.setEventListener(new MediaPlayer.EventListener(){public void onEvent(MediaPlayer.Event event){
                if(!active(token)||player==null)return;
                switch(event.type){
                    case MediaPlayer.Event.Opening:status("正在打开视频");break;
                    case MediaPlayer.Event.Buffering:if(event.getBuffering()<100)status("缓冲 "+(int)event.getBuffering()+"%");break;
                    case MediaPlayer.Event.Playing:
                        status("正在播放 · 确定键打开控制栏，下载管理可查看本地缓存");
                        if(resumeAt>0){player.setTime(resumeAt);resumeAt=0;}
                        ui.postDelayed(new Runnable(){public void run(){if(active(token)&&player!=null&&player.isPlaying()&&!seeking)showControls(false);}},8000);break;
                    case MediaPlayer.Event.Vout:if(event.getVoutCount()>0){seenVideo=true;cover.setVisibility(View.GONE);android.util.Log.i("KanDongman","video-output ready");}break;
                    case MediaPlayer.Event.EncounteredError:failed(token);break;
                    case MediaPlayer.Event.EndReached:status("本集播放结束，可选择下一集");showControls(true);savePosition();break;
                }
            }});
            Media media=new Media(vlc,Uri.parse(address));
            try {media.setHWDecoderEnabled(mode!=2&&!software,mode==1);media.addOption(":network-caching=10000");media.addOption(":file-caching=1500");media.addOption(":http-user-agent="+source.agent());media.addOption(":http-referrer="+(source.rule.optString("referer").isEmpty()?urls.get(index):source.rule.optString("referer")));player.setMedia(media);}finally{media.release();}
            player.play();
            ui.postDelayed(new Runnable(){public void run(){if(!active(token)||seenVideo)return;if(mode==0&&!softRetry)beginMedia(address,true);else{clearPlayer();status("未能加载画面，请返回换线路或来源。");showControls(true);}}},25000);
        }catch(Throwable error){clearPlayer();status("播放器错误："+error.getClass().getSimpleName());showControls(true);}
    }
    private void failed(final int token){ui.post(new Runnable(){public void run(){if(!active(token))return;if(mode==0&&!softRetry)beginMedia(mediaUrl,true);else{clearPlayer();status("这条线路暂时无法播放，请返回换线路或来源。");showControls(true);}}});}
    private void step(int direction){if(index+direction<0||index+direction>=urls.size()){Toast.makeText(this,direction>0?"已经是最后一集":"已经是第一集",Toast.LENGTH_SHORT).show();return;}savePosition();index+=direction;resumeAt=0;loadEpisode();}
    private void selectEpisode(){AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择剧集").setSingleChoiceItems(names.toArray(new String[0]),index,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){d.dismiss();savePosition();index=which;resumeAt=0;loadEpisode();}}).setNegativeButton("返回",null).show();TvStyle.dialog(dialog);}
    private void decoder(){AlertDialog dialog=new AlertDialog.Builder(this).setTitle("解码方式").setSingleChoiceItems(modes,mode,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){d.dismiss();mode=which;prefs.edit().putInt("mode",mode).apply();resumeAt=player==null?resumeAt:Math.max(0,player.getTime());if(!mediaUrl.isEmpty())beginMedia(mediaUrl,false);}}).setNegativeButton("返回",null).show();TvStyle.dialog(dialog);}
    private void saveHistory(){if(cacheOnly!=null)return;try{JSONObject h=new JSONObject();h.put("title",title);h.put("source",source.id);h.put("names",new JSONArray(names));h.put("urls",new JSONArray(urls));prefs.edit().putString("history",h.toString()).putInt("lastIndex",index).putLong("lastPosition",resumeAt).apply();WatchHistory.save(this,h,index,resumeAt);}catch(Exception ignored){}}
    private void savePosition(){if(cacheOnly!=null){if(player!=null&&player.getTime()>=0)prefs.edit().putLong("cachePosition/"+cacheOnly,player.getTime()).apply();lastSave=System.currentTimeMillis();return;}if(player!=null){long at=player.getTime();if(at>=0&&(resumeAt==0||player.isPlaying())){prefs.edit().putInt("lastIndex",index).putLong("lastPosition",at).apply();WatchHistory.update(this,source.id,title,index,at);}}lastSave=System.currentTimeMillis();}
    @Override protected void onStart(){super.onStart();foreground=true;if(vlc!=null&&player==null&&!resolving)loadEpisode();}
    @Override protected void onStop(){savePosition();if(player!=null&&(player.getTime()>0||resumeAt==0))resumeAt=Math.max(0,player.getTime());foreground=false;generation++;resolving=false;clearBrowser();if(root!=null)clearPlayer();super.onStop();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);worker.shutdownNow();if(vlc!=null){vlc.release();vlc=null;}super.onDestroy();}
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_MENU||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(controls!=null&&controls.getVisibility()!=View.VISIBLE){showControls(true);return true;}if(key==KeyEvent.KEYCODE_MENU){showControls(true);return true;}}
        if(controls!=null&&controls.getVisibility()!=View.VISIBLE&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){if(player!=null)player.setTime(Math.max(0,player.getTime()+(key==KeyEvent.KEYCODE_DPAD_RIGHT?10000:-10000)));return true;}
        return super.onKeyDown(key,event);
    }
}
