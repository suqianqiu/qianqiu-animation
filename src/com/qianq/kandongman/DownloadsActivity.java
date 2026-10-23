package com.qianq.kandongman;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import android.widget.*;
import android.view.*;
import java.util.*;

public final class DownloadsActivity extends Activity {
    private DownloadStore store;private TextView summary;private ListView list;private ArrayAdapter<String> adapter;private final ArrayList<String> rows=new ArrayList<String>();private ArrayList<DownloadStore.Task> tasks;private Button auto;private int filter=0;private boolean nameSort;private final Handler ui=new Handler();
    private final Runnable refresh=new Runnable(){public void run(){render();ui.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(1024,1024);store=DownloadStore.get(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(32,20,32,20);root.setBackgroundColor(0xFF151727);setContentView(root);
        TextView title=new TextView(this);title.setText("下载管理 · 本地缓存");title.setTextColor(Color.WHITE);title.setTextSize(28);root.addView(title);
        LinearLayout bar=new LinearLayout(this);root.addView(bar);
        auto=button(bar,"仅手动下载",new Runnable(){public void run(){Toast.makeText(DownloadsActivity.this,"仅点击播放页缓存下载或列表下载才创建后台任务",Toast.LENGTH_LONG).show();}});
        button(bar,"缓存上限",new Runnable(){public void run(){final int[] sizes={1,4,8,16};AlertDialog d=new AlertDialog.Builder(DownloadsActivity.this).setTitle("缓存上限（不自动删除已下载内容）").setItems(new String[]{"1 GB","4 GB（默认）","8 GB","16 GB"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){getSharedPreferences("anime",0).edit().putInt("cacheGB",sizes[n]).apply();render();}}).setNegativeButton("返回",null).show();TvStyle.dialog(d);}});
        button(bar,"全部暂停",new Runnable(){public void run(){for(DownloadStore.Task t:store.list())if(!t.complete())store.pause(t);render();}});
        button(bar,"继续未完成",new Runnable(){public void run(){DownloadService.start(DownloadsActivity.this);for(DownloadStore.Task t:store.list())if(!t.complete()){t.paused=false;store.enqueue(t);}render();}});
        LinearLayout filters=new LinearLayout(this);root.addView(filters);
        button(filters,"全部任务",new Runnable(){public void run(){filter=0;render();}});
        button(filters,"未完成",new Runnable(){public void run(){filter=1;render();}});
        button(filters,"已下载文件",new Runnable(){public void run(){filter=2;render();}});
        button(filters,"排序",new Runnable(){public void run(){nameSort=!nameSort;render();}});
        button(filters,"清理已下载",new Runnable(){public void run(){AlertDialog d=new AlertDialog.Builder(DownloadsActivity.this).setTitle("删除全部已下载文件？").setMessage("仅删除已完成的本地缓存。正在下载的任务会保留。若正在播放缓存，请先退出播放。").setPositiveButton("删除",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){for(DownloadStore.Task t:store.list())if(t.complete())store.delete(t);render();}}).setNegativeButton("保留",null).show();TvStyle.dialog(d);}});
        summary=new TextView(this);summary.setTextColor(0xFFCDCDE0);summary.setTextSize(16);root.addView(summary);
        list=new ListView(this);list.setSelector(TvStyle.rows(this));adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){public View getView(int p,View v,ViewGroup g){TextView t=(TextView)super.getView(p,v,g);t.setTextColor(Color.WHITE);t.setTextSize(19);t.setPadding(20,16,20,16);return t;}};list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int n,long id){if(n<tasks.size())actions(tasks.get(n));}});list.requestFocus();
    }
    private Button button(LinearLayout row,String text,final Runnable r){Button b=new Button(this);b.setText(text);b.setTextSize(17);TvStyle.button(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){r.run();}});row.addView(b,new LinearLayout.LayoutParams(0,64,1));return b;}
    private void render(){int selection=list.getSelectedItemPosition();tasks=store.list();for(Iterator<DownloadStore.Task> it=tasks.iterator();it.hasNext();){DownloadStore.Task t=it.next();if(filter==1&&t.complete()||filter==2&&!t.complete())it.remove();}
        if(nameSort)Collections.sort(tasks,new Comparator<DownloadStore.Task>(){public int compare(DownloadStore.Task a,DownloadStore.Task b){int n=java.text.Collator.getInstance(Locale.CHINA).compare(a.title,b.title);if(n!=0)return n;return natural(a.episode).compareTo(natural(b.episode));}});else Collections.reverse(tasks);
        rows.clear();for(DownloadStore.Task t:tasks){int done=t.done(),total=t.resources.size();rows.add(t.title+" · "+t.episode+"\n"+(t.complete()?"已完成 · 可离线播放":t.state)+"  "+(t.hls?(total>0?done*100/total:0)+"%":"按文件字节下载")+" · "+done+"/"+total+" 个资源 · "+DownloadStore.mb(t.bytes+t.inflight)+(t.running&&!t.paused?" · "+DownloadStore.mb(t.speed)+"/s":"")+(t.error.isEmpty()?"":"\n"+t.error));}adapter.notifyDataSetChanged();if(selection>=0&&selection<rows.size())list.setSelection(selection);summary.setText((filter==2?"已下载文件":filter==1?"未完成任务":"全部任务")+" · "+tasks.size()+" 项 · "+(nameSort?"作品 / 集数排序":"最近添加在前")+"\n已用 "+DownloadStore.mb(store.used())+" / "+(store.limit()/1073741824)+" GB · 剩余 "+DownloadStore.mb(getFilesDir().getUsableSpace())+"\n仅手动下载；已确认的任务可在退出播放后继续。点选任务管理。MP4 暂停后重新下载，HLS 保留完整片段。");}
    private void actions(final DownloadStore.Task t){final String[] options={t.complete()?"文件信息":t.paused||t.state.equals("下载失败")?"继续 / 重试下载":"暂停后台下载",t.complete()?"离线播放":"缓存播放（HLS 可边下边看）","删除任务及本地文件"};AlertDialog d=new AlertDialog.Builder(this).setTitle(t.title+" · "+t.episode).setItems(options,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){if(which==0){if(t.complete()){info(t);return;}if(t.paused||t.state.equals("下载失败")){DownloadService.start(DownloadsActivity.this);store.enqueue(t);}else store.pause(t);}else if(which==1){if(!t.hls&&!t.complete()){Toast.makeText(DownloadsActivity.this,"MP4 请下载完成后离线播放",Toast.LENGTH_LONG).show();return;}if(t.paused&&!t.complete()){Toast.makeText(DownloadsActivity.this,"请先继续下载",Toast.LENGTH_LONG).show();return;}Intent i=new Intent(DownloadsActivity.this,PlayerActivity.class);i.putExtra("cacheId",t.id);startActivity(i);}else{AlertDialog confirm=new AlertDialog.Builder(DownloadsActivity.this).setTitle("删除此集缓存？").setMessage("如果正在播放此集，请先退出播放。").setPositiveButton("删除",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){store.delete(t);render();}}).setNegativeButton("保留",null).show();TvStyle.dialog(confirm);}render();}}).setNegativeButton("返回",null).show();TvStyle.dialog(d);}
    private static String natural(String s){java.util.regex.Matcher m=java.util.regex.Pattern.compile("[0-9]+").matcher(s);StringBuffer out=new StringBuffer();while(m.find())m.appendReplacement(out,String.format(Locale.US,"%010d",Long.parseLong(m.group())));m.appendTail(out);return out.toString();}
    private void info(DownloadStore.Task t){AlertDialog d=new AlertDialog.Builder(this).setTitle("已下载文件 · "+t.episode).setMessage(t.title+"\n格式："+(t.hls?"HLS 分片缓存":"视频文件")+"\n大小："+DownloadStore.mb(DownloadStore.size(t.directory))+"\n资源："+t.done()+" / "+t.resources.size()+"\n本地路径："+t.directory.getAbsolutePath()+"\n在应用内选择离线播放；HLS 缓存由播放列表和分片组成。").setPositiveButton("知道了",null).show();TvStyle.dialog(d);}
    @Override protected void onStart(){super.onStart();ui.post(refresh);}
    @Override protected void onStop(){ui.removeCallbacks(refresh);super.onStop();}
}
