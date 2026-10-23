package com.qianq.kandongman;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newFixedThreadPool(3);
    private final ArrayDeque<Screen> back=new ArrayDeque<Screen>();
    private ArrayList<Sources.Source> sources;
    private Sources.Source source;
    private Screen current;
    private TextView status,heading;
    private EditText query;
    private GridView list;
    private final android.util.LruCache<String,android.graphics.Bitmap> posters=new android.util.LruCache<String,android.graphics.Bitmap>(36);
    private int sortMode=0;
    private int downloadSelection=-1;
    private AlertDialog downloadDialog;
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> labels=new ArrayList<String>();
    private SharedPreferences prefs;
    private int generation;
    private final ArrayList<AnimeCatalog.Entry> catalog=AnimeCatalog.load();
    private char letter='*';
    private boolean settingQuery;
    private int hiddenTitles;
    private LinearLayout alphabet;
    private final ArrayList<Button> letterButtons=new ArrayList<Button>();
    private final ArrayList<Button> tabButtons=new ArrayList<Button>();
    private static final int HOT=0,ALL=1,HISTORY=2,CLASSIC=3;
    private int tab=HOT;
    private JSONArray popular=new JSONArray();
    private String ratingDate="";
    private String ratingInfo="";
    private static final class Screen {
        final String title;
        final ArrayList<String> names=new ArrayList<String>();
        final ArrayList<Runnable> actions=new ArrayList<Runnable>();
        final ArrayList<String> links=new ArrayList<String>();
        final ArrayList<Runnable> downloads=new ArrayList<Runnable>();
        final ArrayList<String> covers=new ArrayList<String>();
        int position;
        boolean catalog;
        int tab=-1;
        String filter="";
        char letter='*';
        Screen(String title){this.title=title;}
        void add(String name,Runnable action){add(name,action,"");}
        void add(String name,Runnable action,String url){names.add(name);actions.add(action);links.add(url);covers.add("");downloads.add(null);}
        int prune(){int count=0;for(int i=links.size()-1;i>=0;i--)if(LinkHealth.isBlocked(links.get(i))){links.remove(i);covers.remove(i);downloads.remove(i);names.remove(i);actions.remove(i);count++;}position=Math.max(0,Math.min(position,names.size()-1));return count;}
    }
    private interface Work {Screen run() throws Exception;}
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(1024,1024);
        prefs=getSharedPreferences("anime",MODE_PRIVATE);sortMode=prefs.getInt("catalogSort",0);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(24),dp(18),dp(24),dp(14));root.setBackgroundColor(0xFF151727);setContentView(root);
        TextView brand=text("千秋动画",30,0xFFE1D5FF);brand.setTypeface(null,Typeface.BOLD);root.addView(brand);
        TextView guide=text("豆瓣高分 · 全部动漫 · 观看历史",16,0xFFB7B6C9);guide.setPadding(0,dp(5),0,dp(8));root.addView(guide);
        LinearLayout bar=new LinearLayout(this);
        query=new EditText(this);query.setSingleLine();query.setTextSize(20);query.setTextColor(Color.WHITE);query.setHintTextColor(0xFFAAA9BD);query.setHint("名称或首字母，如 mztkn / hzw");query.setImeOptions(3);
        bar.addView(query,new LinearLayout.LayoutParams(0,dp(50),1));
        button(bar,"搜索",new Runnable(){public void run(){search(query.getText().toString());}});
        button(bar,"目录",new Runnable(){public void run(){selectTab(ALL,true);}});
        final Spinner picker=new Spinner(this);TvStyle.focusable(picker);bar.addView(picker,new LinearLayout.LayoutParams(dp(190),dp(50)));
        button(bar,"继续观看",new Runnable(){public void run(){resume();}});
        button(bar,"排序",new Runnable(){public void run(){AlertDialog d=new AlertDialog.Builder(MainActivity.this).setTitle("排序方式").setItems(new String[]{"热门评分从高到低 / 目录 A–Z","作品名称 A–Z","作品名称 Z–A"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){sortMode=which;prefs.edit().putInt("catalogSort",which).apply();renderHome(true);}}).setNegativeButton("返回",null).show();TvStyle.dialog(d);}});
        button(bar,"下载管理",new Runnable(){public void run(){startActivity(new Intent(MainActivity.this,DownloadsActivity.class));}});
        root.addView(bar);
        LinearLayout tabs=new LinearLayout(this);root.addView(tabs);
        String[] tabNames={"热门海报","全部动漫","历史","经典怀旧"};
        for(int i=0;i<tabNames.length;i++){
            final int target=i;Button b=new Button(this);b.setText(tabNames[i]);b.setTextSize(19);TvStyle.button(b);
            b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){selectTab(target,false);}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);lp.setMargins(dp(3),dp(6),dp(3),dp(6));tabs.addView(b,lp);tabButtons.add(b);
        }
        alphabet=new LinearLayout(this);alphabet.setOrientation(1);root.addView(alphabet);
        String[] letters={"全部","A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z","#"};
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(this);alphabet.addView(line);
            for(int col=0;col<14;col++){
                final String name=letters[row*14+col];final char value=name.equals("全部")?'*':name.charAt(0);
                Button key=new Button(this);key.setText(name);key.setTextSize(16);TvStyle.button(key);key.setPadding(0,0,0,0);key.setTag(Character.valueOf(value));
                key.setOnClickListener(new View.OnClickListener(){public void onClick(View v){generation++;letter=value;setQuery("");renderCatalog(false);}});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(36),1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));line.addView(key,lp);letterButtons.add(key);
            }
        }
        heading=text("推荐搜索",21,0xFFF5DE9A);heading.setPadding(0,dp(14),0,dp(8));root.addView(heading);
        list=new GridView(this);list.setFocusableInTouchMode(true);list.setNumColumns(1);list.setVerticalSpacing(dp(6));list.setHorizontalSpacing(dp(8));list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);list.setSelector(TvStyle.rows(this));list.setDrawSelectorOnTop(false);
        adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                if(current!=null&&current.tab==HOT&&position<current.covers.size()){
                    LinearLayout card=new LinearLayout(MainActivity.this);card.setOrientation(1);card.setPadding(dp(6),dp(6),dp(6),dp(8));
                    ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(0xFF202638);String path=current.covers.get(position);
                    android.graphics.Bitmap bitmap=posters.get(path);if(bitmap==null&&!path.isEmpty())try(java.io.InputStream in=getAssets().open(path)){bitmap=android.graphics.BitmapFactory.decodeStream(in);if(bitmap!=null)posters.put(path,bitmap);}catch(Exception ignored){}image.setImageBitmap(bitmap);card.addView(image,new LinearLayout.LayoutParams(-1,dp(300)));
                    TextView title=text(labels.get(position),16,Color.WHITE);title.setMaxLines(3);title.setMinHeight(dp(62));card.addView(title);return card;
                }
                final Screen screen=current;final int itemIndex=position;
                if(screen!=null&&position<screen.downloads.size()&&screen.downloads.get(position)!=null){
                    LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(4),dp(8),dp(4));
                    TextView name=text(labels.get(position),20,Color.WHITE);name.setPadding(dp(10),dp(10),dp(10),dp(10));name.setMinHeight(dp(60));row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                    Button download=new Button(MainActivity.this);download.setText("下载");download.setTextSize(18);TvStyle.button(download);download.setFocusable(false);download.setActivated(downloadSelection==position);download.setOnClickListener(new View.OnClickListener(){public void onClick(View v){screen.downloads.get(itemIndex).run();}});row.addView(download,new LinearLayout.LayoutParams(dp(100),dp(50)));return row;
                }
                TextView row=(TextView)super.getView(position,null,parent);row.setTextColor(Color.WHITE);row.setTextSize(21);row.setMinHeight(dp(54));return row;
            }
        };
        list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        status=text("正在准备…",15,0xFFBBBCCF);status.setMaxLines(3);status.setPadding(0,dp(8),0,0);root.addView(status);
        list.setOnKeyListener(new View.OnKeyListener(){public boolean onKey(View v,int key,KeyEvent event){if(event.getAction()!=KeyEvent.ACTION_DOWN||current==null)return false;int selected=list.getSelectedItemPosition();if(selected<0||selected>=current.downloads.size()||current.downloads.get(selected)==null)return false;if(key==KeyEvent.KEYCODE_DPAD_RIGHT){downloadSelection=selected;adapter.notifyDataSetChanged();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT&&downloadSelection>=0){downloadSelection=-1;adapter.notifyDataSetChanged();return true;}if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&downloadSelection==selected){current.downloads.get(selected).run();return true;}if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){downloadSelection=-1;adapter.notifyDataSetChanged();}return false;}});
        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int pos,long id){if(current!=null&&pos<current.actions.size()){current.position=pos;current.actions.get(pos).run();}}});
        query.setOnEditorActionListener(new TextView.OnEditorActionListener(){public boolean onEditorAction(TextView v,int action,android.view.KeyEvent event){if(action==3||action==6||(event!=null&&event.getKeyCode()==66&&event.getAction()==1)){search(query.getText().toString());return true;}return false;}});
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){if(!settingQuery&&source!=null){generation++;letter='*';renderCatalog(false);}} public void afterTextChanged(Editable s){}});
        try {
            sources=Sources.load(this);
            try(java.io.InputStream in=getAssets().open("douban-popular.json")){JSONObject data=new JSONObject(Sources.read(in,1024*1024));popular=data.getJSONArray("items");ratingDate=data.getString("date");ratingInfo=data.getString("method")+"\n\n采集日期："+ratingDate+"\n\n";JSONArray urls=data.getJSONArray("sources");for(int i=0;i<urls.length();i++)ratingInfo+=urls.getString(i)+"\n\n";}
            try{WatchHistory.list(this);}catch(Exception ignored){}
            ArrayList<String> names=new ArrayList<String>();for(Sources.Source s:sources)names.add(s.name);
            ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){@Override public View getView(int pos,View old,ViewGroup parent){TextView v=(TextView)super.getView(pos,old,parent);v.setTextColor(picker.hasFocus()?0xFF14182A:Color.WHITE);v.setTextSize(17);return v;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);picker.setAdapter(a);
            picker.setOnFocusChangeListener(new View.OnFocusChangeListener(){public void onFocusChange(View view,boolean focused){View selected=picker.getSelectedView();if(selected instanceof TextView)((TextView)selected).setTextColor(focused?0xFF14182A:Color.WHITE);}});
            picker.setSelection(Math.max(0,Math.min(sources.size()-1,prefs.getInt("source",0))));
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){} public void onItemSelected(AdapterView<?> p,View v,int pos,long id){source=sources.get(pos);prefs.edit().putInt("source",pos).apply();generation++;back.clear();home();}});
            source=sources.get(picker.getSelectedItemPosition());home();
        } catch(Exception error){status.setText("来源配置读取失败："+error.getMessage());}
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void button(LinearLayout bar,String title,final Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(17);TvStyle.button(b);b.setPadding(dp(14),0,dp(14),0);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(46));lp.setMargins(dp(3),dp(2),dp(3),dp(2));bar.addView(b,lp);}
    private void show(Screen screen,boolean push) {
        if(push&&current!=null){current.position=list.getSelectedItemPosition();back.push(current);}
        downloadSelection=-1;screen.prune();list.setNumColumns(screen.tab==HOT?5:1);current=screen;heading.setText(screen.title);labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();
        if(screen.tab>=0){tab=screen.tab;updateTabs();if(!screen.catalog)setQuery("");}
        alphabet.setVisibility(screen.catalog?View.VISIBLE:View.GONE);
        if(screen.catalog){letter=screen.letter;setQuery(screen.filter);updateLetters();}
        list.requestFocus();list.setSelection(Math.max(0,screen.position));
    }
    private void home() {
        renderHome(true);
    }
    private void updateTabs(){for(int i=0;i<tabButtons.size();i++)tabButtons.get(i).setActivated(i==tab);}
    private void selectTab(int target,boolean focus){generation++;back.clear();tab=target;letter='*';setQuery("");renderHome(focus);}
    private void renderHome(boolean focus){updateTabs();if(tab==ALL)renderCatalog(focus);else if(tab==HISTORY)renderHistory(focus);else if(tab==CLASSIC)renderCatalog(focus);else renderPopular(focus);}
    private void homeScreen(Screen screen,boolean focus){downloadSelection=-1;
        list.setNumColumns(screen.tab==HOT?5:1);current=screen;heading.setText(screen.title);labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();alphabet.setVisibility(screen.catalog?View.VISIBLE:View.GONE);updateTabs();list.setSelection(Math.max(0,screen.position));if(focus)list.requestFocus();
    }
    private void sortPopular(){final ArrayList<JSONObject> items=new ArrayList<JSONObject>();for(int i=0;i<popular.length();i++)items.add(popular.optJSONObject(i));Collections.sort(items,new Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){int n=sortMode==0?Double.compare(b.optDouble("rating"),a.optDouble("rating")):0;if(n==0){AnimeCatalog.Entry ea=AnimeCatalog.identify(a.optString("catalogTitle")),eb=AnimeCatalog.identify(b.optString("catalogTitle"));n=(ea==null?a.optString("catalogTitle"):ea.initials).compareTo(eb==null?b.optString("catalogTitle"):eb.initials);}return sortMode==2?-n:n;}});popular=new JSONArray(items);}
    private void renderPopular(boolean focus){sortPopular();
        Screen screen=new Screen("热门 · "+(sortMode==0?"评分从高到低":sortMode==1?"名称 A–Z":"名称 Z–A")+" · "+ratingDate);screen.tab=HOT;
        for(int i=0;i<popular.length();i++)try{
            JSONObject item=popular.getJSONObject(i);final String title=AnimeCatalog.canonical(item.getString("catalogTitle"));
            screen.add(String.format(Locale.CHINA,"%.1f 分 · %s",item.getDouble("rating"),item.getString("title")),new Runnable(){public void run(){setQuery(title);onlineSearch(title);}});screen.covers.set(screen.covers.size()-1,item.optString("poster"));
        }catch(Exception ignored){}
        homeScreen(screen,focus);status.setText("海报对应评分作品/季 · 可切换排序 · 显示具体作品/季的分数 · "+ratingDate+" 评分快照，共 "+screen.names.size()+" 项；菜单可查看评分来源。确定搜索播放。");
    }
    private void renderHistory(boolean focus){
        Screen screen=new Screen("历史 · 最近观看");screen.tab=HISTORY;int position=current!=null&&current.tab==HISTORY?current.position:0;
        try{for(final JSONObject item:WatchHistory.list(this)){
            int index=Math.max(0,Math.min(item.getJSONArray("names").length()-1,item.optInt("index")));long seconds=item.optLong("position")/1000;
            screen.add(item.getString("title")+"  ·  "+item.getJSONArray("names").getString(index)+String.format(Locale.US,"  ·  %02d:%02d",seconds/60,seconds%60),new Runnable(){public void run(){launchHistory(item);}});
        }}catch(Exception error){status.setText("历史读取失败："+error.getMessage());}
        screen.position=position;homeScreen(screen,focus);status.setText(screen.names.isEmpty()?"还没有观看记录；从热门或全部动漫选择一部开始观看。":"最近 "+screen.names.size()+" 部 · 按最近观看时间排序 · 确定继续上次剧集和进度 · 最多保留20部");
    }
    private void setQuery(String value){settingQuery=true;query.setText(value);query.setSelection(query.length());settingQuery=false;}
    private void updateLetters(){for(Button b:letterButtons){boolean selected=((Character)b.getTag()).charValue()==letter;b.setActivated(selected);b.setText(b.getTag().equals(Character.valueOf('*'))?(selected?"✓全部":"全部"):(selected?"✓":"")+b.getTag().toString());}}
    private void renderCatalog(boolean focus) {
        if(tab!=CLASSIC)tab=ALL;updateTabs();
        String filter=query.getText().toString();
        ArrayList<AnimeCatalog.Entry> matches=AnimeCatalog.filter(catalog,filter,letter);
        if(tab==CLASSIC)for(Iterator<AnimeCatalog.Entry> it=matches.iterator();it.hasNext();)if(!AnimeCatalog.classic(it.next().title))it.remove();
        if(sortMode==2)Collections.reverse(matches);
        Screen screen=new Screen((tab==CLASSIC?"经典怀旧 ":"全部动漫 ")+matches.size()+" / "+catalog.size()+" · "+(letter=='*'?"全部":String.valueOf(letter))+" · "+source.name);
        screen.catalog=true;screen.tab=tab;screen.filter=filter;screen.letter=letter;
        for(final AnimeCatalog.Entry entry:matches)screen.add(entry.title+"   ·   "+entry.initials.toUpperCase(Locale.ROOT)+(entry.aliases().isEmpty()?"":"   · 别名："+entry.aliases()),new Runnable(){public void run(){setQuery(entry.title);onlineSearch(entry.title);}});
        list.setNumColumns(1);current=screen;heading.setText(screen.title);alphabet.setVisibility(View.VISIBLE);updateLetters();
        labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();list.setSelection(0);if(focus)list.requestFocus();
        status.setText(matches.isEmpty()?"目录没有匹配项；可输入完整名称后按搜索，查询当前来源。":"名称或别名均可搜索 · 同作品译名合并 · A–Z 排序 · 来源未收录的经典仍保留目录入口。");
    }
    private void task(String message,final Work work) {
        final int token=++generation;status.setText(message);
        worker.execute(new Runnable(){public void run(){
            try {final Screen screen=work.run();ui.post(new Runnable(){public void run(){if(isDestroyed()||isFinishing()||token!=generation)return;show(screen,true);status.setText(screen.names.isEmpty()?"没有找到结果，可换来源或使用更短的关键词。":"共 "+screen.names.size()+" 项 · 确定打开 · 返回上一级");}});}
            catch(final Exception e){ui.post(new Runnable(){public void run(){if(isDestroyed()||isFinishing()||token!=generation)return;if(current!=null&&current.prune()>0)show(current,false);status.setText("未能完成："+e.getMessage()+"。可换一个来源重试。");list.requestFocus();}});}
        }});
    }
    private void search(String keyword) {
        String term=keyword.trim();if(source==null||term.isEmpty()){letter='*';home();return;}
        ArrayList<AnimeCatalog.Entry> matches=AnimeCatalog.filter(catalog,term,'*');
        for(AnimeCatalog.Entry entry:matches)if(AnimeCatalog.normalize(entry.title).equals(AnimeCatalog.normalize(term))||(matches.size()==1&&entry.exact(term))){setQuery(entry.title);onlineSearch(entry.title);return;}
        if(!matches.isEmpty()){letter='*';renderCatalog(true);status.setText("匹配 "+matches.size()+" 部动漫；选中中文名称按确定，查询播放来源。");return;}
        onlineSearch(term);
    }
    private void onlineSearch(final String term) {
        prefs.edit().putString("query",term).apply();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
        final Sources.Source chosen=source;
        final String sourceTerm=LinkHealth.searchTerm(term,chosen.id);
        task("正在搜索 · "+chosen.name,new Work(){public Screen run() throws Exception {
            ArrayList<Sources.Item> found=Sources.search(chosen,sourceTerm);
            if(found.isEmpty())for(String alias:AnimeCatalog.searchNames(term)){if(alias.equals(term)||alias.equals(sourceTerm))continue;found=Sources.search(chosen,alias);if(!found.isEmpty())break;}
            Collections.sort(found,new Comparator<Sources.Item>(){public int compare(Sources.Item a,Sources.Item b){return MediaLabels.compare(a.name,b.name);}});
            Screen result=new Screen("“"+term+"” · "+chosen.name);
            for(final Sources.Item item:found){result.add(MediaLabels.display(item.name),new Runnable(){public void run(){roads(chosen,item);}},item.url);result.downloads.set(result.downloads.size()-1,new Runnable(){public void run(){downloadSeries(chosen,item);}});}
            return result;
        }});
    }
    private void roads(final Sources.Source chosen,final Sources.Item series) {
        task("正在获取分集 · "+series.name,new Work(){public Screen run() throws Exception {
            final ArrayList<Sources.Road> roads=Sources.chapters(chosen,series.url);
            Collections.sort(roads,new Comparator<Sources.Road>(){public int compare(Sources.Road a,Sources.Road b){return MediaLabels.compare(series.name+" "+a.name,series.name+" "+b.name);}});
            Screen result=new Screen(series.name+" · 选择线路");
            for(final Sources.Road road:roads){result.add(MediaLabels.display(series.name+" · "+road.name),new Runnable(){public void run(){episodes(chosen,series,road);}});result.downloads.set(result.downloads.size()-1,new Runnable(){public void run(){confirmDownloads(chosen,series,road,road.episodes);}});}
            return result;
        }});
    }
    private void episodes(final Sources.Source chosen,final Sources.Item series,final Sources.Road road) {
        Screen screen=new Screen(series.name+" · "+road.name);
        for(int i=0;i<road.episodes.size();i++){
            final int position=i;screen.add(MediaLabels.display(series.name+" "+road.name)+" · "+road.episodes.get(i).name,new Runnable(){public void run(){
                ArrayList<String> names=new ArrayList<String>(),urls=new ArrayList<String>();
                int selected=-1;for(Sources.Item episode:road.episodes)if(!LinkHealth.isBlocked(episode.url)){if(episode.url.equals(road.episodes.get(position).url))selected=names.size();names.add(episode.name);urls.add(episode.url);}
                if(selected<0){Toast.makeText(MainActivity.this,"该地址已失效，请选其他剧集",Toast.LENGTH_SHORT).show();return;}
                launch(chosen.id,series.name+" · "+MediaLabels.language(series.name+" "+road.name),names,urls,selected,0);
            }},road.episodes.get(i).url);
            screen.downloads.set(screen.downloads.size()-1,new Runnable(){public void run(){ArrayList<Sources.Item> one=new ArrayList<Sources.Item>();one.add(road.episodes.get(position));confirmDownloads(chosen,series,road,one);}});
        }
        show(screen,true);status.setText("确定进入播放 · 右键选择下载，再按确定 · 左键返回剧集名称。");
    }
    private void downloadSeries(final Sources.Source source,final Sources.Item series){
        task("选择要下载的线路 · "+series.name,new Work(){public Screen run()throws Exception{
            ArrayList<Sources.Road> roads=Sources.chapters(source,series.url);Collections.sort(roads,new Comparator<Sources.Road>(){public int compare(Sources.Road a,Sources.Road b){return MediaLabels.compare(series.name+" "+a.name,series.name+" "+b.name);}});
            Screen result=new Screen(series.name+" · 选择下载线路");for(final Sources.Road road:roads){Runnable download=new Runnable(){public void run(){confirmDownloads(source,series,road,road.episodes);}};result.add(MediaLabels.display(road.name),download);result.downloads.set(result.downloads.size()-1,download);}return result;
        }});
    }
    private void confirmDownloads(final Sources.Source source,final Sources.Item series,final Sources.Road road,final ArrayList<Sources.Item> episodes){
        AlertDialog d=new AlertDialog.Builder(this).setTitle("加入后台下载").setMessage(series.name+"\n"+MediaLabels.display(road.name)+"\n共 "+episodes.size()+" 集；已加入的剧集不会重复创建。可在下载管理暂停或删除。").setPositiveButton("下载",new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int which){
            DownloadService.start(MainActivity.this);status.setText("正在加入下载队列…");worker.execute(new Runnable(){public void run(){try{DownloadStore store=DownloadStore.get(MainActivity.this);for(Sources.Item episode:episodes)if(!LinkHealth.isBlocked(episode.url))store.enqueueEpisode(source,episode.url,series.name+" · "+MediaLabels.language(series.name+" "+road.name),episode.name);ui.post(new Runnable(){public void run(){if(!isDestroyed()){status.setText("已加入后台下载；右上角下载管理查看进度。");Toast.makeText(MainActivity.this,"已加入下载队列",Toast.LENGTH_SHORT).show();}}});}catch(final Exception e){ui.post(new Runnable(){public void run(){status.setText("加入下载失败："+e.getMessage());}});}}});
        }}).setNegativeButton("返回",null).show();downloadDialog=d;TvStyle.dialog(d);
    }
    private void launch(String sourceId,String title,ArrayList<String> names,ArrayList<String> urls,int index,long position) {
        Intent intent=new Intent(this,PlayerActivity.class);intent.putExtra("source",sourceId);intent.putExtra("title",title);intent.putStringArrayListExtra("names",names);intent.putStringArrayListExtra("urls",urls);intent.putExtra("index",index);intent.putExtra("position",position);startActivity(intent);
    }
    private void resume() {
        try {ArrayList<JSONObject> items=WatchHistory.list(this);if(items.isEmpty())throw new Exception();launchHistory(items.get(0));}
        catch(Exception e){Toast.makeText(this,"还没有播放记录",Toast.LENGTH_SHORT).show();}
    }
    private void launchHistory(JSONObject h){
        try {
            JSONArray a=h.getJSONArray("names"),b=h.getJSONArray("urls");
            ArrayList<String> names=new ArrayList<String>(),urls=new ArrayList<String>();for(int i=0;i<a.length();i++){names.add(a.getString(i));urls.add(b.getString(i));}
            launch(h.getString("source"),h.getString("title"),names,urls,h.optInt("index"),h.optLong("position"));
        }catch(Exception e){Toast.makeText(this,"这条历史记录无法打开",Toast.LENGTH_SHORT).show();}
    }
    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(key==KeyEvent.KEYCODE_MENU){if(event.getRepeatCount()==0){AlertDialog dialog=new AlertDialog.Builder(this).setTitle("千秋动画 1.4.0").setMessage("面向遥控器的动漫搜索与播放器。\n\n热门按豆瓣评分降序，显示具体作品/季和快照日期。\n历史保留最近20部及播放进度。\n\n来源规则：KazumiRules（MIT）。\nHTML解析：jsoup（MIT）。\n播放器：VideoLAN LibVLC 3.6.5（LGPL 2.1）。\n应用源码及许可证随安装包交付。\n\n来源页面和线路可能变化；搜索结果不等于每条播放线路都可用。").setNeutralButton("评分来源",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){AlertDialog info=new AlertDialog.Builder(MainActivity.this).setTitle("豆瓣评分来源 · "+ratingDate).setMessage(ratingInfo).setPositiveButton("知道了",null).show();TvStyle.button(info.getButton(-1));}}).setPositiveButton("知道了",null).show();TvStyle.button(dialog.getButton(-1));TvStyle.button(dialog.getButton(-3));}return true;}
        return super.onKeyDown(key,event);
    }
    @Override public void onBackPressed(){generation++;if(!back.isEmpty()){show(back.pop(),false);status.setText("返回上一级 · 菜单查看说明");}else super.onBackPressed();}
    @Override public void onResume(){super.onResume();if(current!=null){if(current.tab==HISTORY){renderHistory(false);return;}int removed=current.prune();if(removed>0){show(current,false);status.setText("已自动移除 "+removed+" 个确认失效的404/410地址，请选其他线路或剧集。");}}}
    @Override public void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}
