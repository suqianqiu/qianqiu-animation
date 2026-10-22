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
    private ListView list;
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
    private static final int HOT=0,ALL=1,HISTORY=2;
    private int tab=HOT;
    private JSONArray popular=new JSONArray();
    private String ratingDate="";
    private String ratingInfo="";
    private static final class Screen {
        final String title;
        final ArrayList<String> names=new ArrayList<String>();
        final ArrayList<Runnable> actions=new ArrayList<Runnable>();
        final ArrayList<String> links=new ArrayList<String>();
        int position;
        boolean catalog;
        int tab=-1;
        String filter="";
        char letter='*';
        Screen(String title){this.title=title;}
        void add(String name,Runnable action){add(name,action,"");}
        void add(String name,Runnable action,String url){names.add(name);actions.add(action);links.add(url);}
        int prune(){int count=0;for(int i=links.size()-1;i>=0;i--)if(LinkHealth.isBlocked(links.get(i))){links.remove(i);names.remove(i);actions.remove(i);count++;}position=Math.max(0,Math.min(position,names.size()-1));return count;}
    }
    private interface Work {Screen run() throws Exception;}
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(1024,1024);
        prefs=getSharedPreferences("anime",MODE_PRIVATE);
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
        root.addView(bar);
        LinearLayout tabs=new LinearLayout(this);root.addView(tabs);
        String[] tabNames={"热门","全部动漫","历史"};
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
        list=new ListView(this);list.setDividerHeight(dp(3));list.setSelector(TvStyle.rows(this));list.setDrawSelectorOnTop(false);
        adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                TextView row=(TextView)super.getView(position,convert,parent);row.setTextColor(Color.WHITE);row.setTextSize(21);row.setMinHeight(dp(54));return row;
            }
        };
        list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        status=text("正在准备…",15,0xFFBBBCCF);status.setMaxLines(3);status.setPadding(0,dp(8),0,0);root.addView(status);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int pos,long id){if(current!=null&&pos<current.actions.size()){current.position=pos;current.actions.get(pos).run();}}});
        query.setOnEditorActionListener(new TextView.OnEditorActionListener(){public boolean onEditorAction(TextView v,int action,android.view.KeyEvent event){if(action==3||action==6||(event!=null&&event.getKeyCode()==66&&event.getAction()==1)){search(query.getText().toString());return true;}return false;}});
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){if(!settingQuery&&source!=null){generation++;letter='*';renderCatalog(false);}} public void afterTextChanged(Editable s){}});
        try {
            sources=Sources.load(this);for(Iterator<AnimeCatalog.Entry> it=catalog.iterator();it.hasNext();)if(LinkHealth.hideTitle(it.next().title)){it.remove();hiddenTitles++;}
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
        screen.prune();current=screen;heading.setText(screen.title);labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();
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
    private void renderHome(boolean focus){updateTabs();if(tab==ALL)renderCatalog(focus);else if(tab==HISTORY)renderHistory(focus);else renderPopular(focus);}
    private void homeScreen(Screen screen,boolean focus){
        current=screen;heading.setText(screen.title);labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();alphabet.setVisibility(screen.catalog?View.VISIBLE:View.GONE);updateTabs();list.setSelection(Math.max(0,screen.position));if(focus)list.requestFocus();
    }
    private void renderPopular(boolean focus){
        Screen screen=new Screen("热门 · 豆瓣高分 · "+ratingDate);screen.tab=HOT;
        for(int i=0;i<popular.length();i++)try{
            JSONObject item=popular.getJSONObject(i);final String title=item.getString("catalogTitle");if(LinkHealth.hideTitle(title))continue;
            screen.add(String.format(Locale.CHINA,"%.1f 分  ·  %s",item.getDouble("rating"),item.getString("title")),new Runnable(){public void run(){setQuery(title);onlineSearch(title);}});
        }catch(Exception ignored){}
        homeScreen(screen,focus);status.setText("豆瓣评分降序 · 显示具体作品/季的分数 · "+ratingDate+" 评分快照，共 "+screen.names.size()+" 项；菜单可查看评分来源。确定搜索播放。");
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
        tab=ALL;updateTabs();
        String filter=query.getText().toString();
        ArrayList<AnimeCatalog.Entry> matches=AnimeCatalog.filter(catalog,filter,letter);
        Screen screen=new Screen("常见动漫 "+matches.size()+" / "+catalog.size()+" · "+(letter=='*'?"全部":String.valueOf(letter))+" · "+source.name);
        screen.catalog=true;screen.tab=ALL;screen.filter=filter;screen.letter=letter;
        for(final AnimeCatalog.Entry entry:matches)screen.add(entry.title+"   ·   "+entry.initials.toUpperCase(Locale.ROOT),new Runnable(){public void run(){setQuery(entry.title);onlineSearch(entry.title);}});
        current=screen;heading.setText(screen.title);alphabet.setVisibility(View.VISIBLE);updateLetters();
        labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();list.setSelection(0);if(focus)list.requestFocus();
        status.setText(matches.isEmpty()?"目录没有匹配项；可输入完整名称后按搜索，查询当前来源。":"中文 / 每字首字母（MZTKN / HZW）；已隐藏 "+hiddenTitles+" 项全来源无搜索结果的名称。404/410自动过滤；可播放性取决于线路。");
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
            ArrayList<Sources.Item> found=Sources.search(chosen,sourceTerm);Screen result=new Screen("“"+term+"” · "+chosen.name);
            for(final Sources.Item item:found)result.add(item.name,new Runnable(){public void run(){roads(chosen,item);}},item.url);
            return result;
        }});
    }
    private void roads(final Sources.Source chosen,final Sources.Item series) {
        task("正在获取分集 · "+series.name,new Work(){public Screen run() throws Exception {
            final ArrayList<Sources.Road> roads=Sources.chapters(chosen,series.url);
            Screen result=new Screen(series.name+" · 选择线路");
            for(final Sources.Road road:roads)result.add(road.name,new Runnable(){public void run(){episodes(chosen,series,road);}});
            return result;
        }});
    }
    private void episodes(final Sources.Source chosen,final Sources.Item series,final Sources.Road road) {
        Screen screen=new Screen(series.name+" · "+road.name);
        for(int i=0;i<road.episodes.size();i++){
            final int position=i;screen.add(road.episodes.get(i).name,new Runnable(){public void run(){
                ArrayList<String> names=new ArrayList<String>(),urls=new ArrayList<String>();
                int selected=-1;for(Sources.Item episode:road.episodes)if(!LinkHealth.isBlocked(episode.url)){if(episode.url.equals(road.episodes.get(position).url))selected=names.size();names.add(episode.name);urls.add(episode.url);}
                if(selected<0){Toast.makeText(MainActivity.this,"该地址已失效，请选其他剧集",Toast.LENGTH_SHORT).show();return;}
                launch(chosen.id,series.name,names,urls,selected,0);
            }},road.episodes.get(i).url);
        }
        show(screen,true);status.setText("选择剧集播放；某条线路失效时返回选择其他线路。");
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
        if(key==KeyEvent.KEYCODE_MENU){if(event.getRepeatCount()==0){AlertDialog dialog=new AlertDialog.Builder(this).setTitle("千秋动画 1.2.0").setMessage("面向遥控器的动漫搜索与播放器。\n\n热门按豆瓣评分降序，显示具体作品/季和快照日期。\n历史保留最近20部及播放进度。\n\n来源规则：KazumiRules（MIT）。\nHTML解析：jsoup（MIT）。\n播放器：VideoLAN LibVLC 3.6.5（LGPL 2.1）。\n应用源码及许可证随安装包交付。\n\n来源页面和线路可能变化；搜索结果不等于每条播放线路都可用。").setNeutralButton("评分来源",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){AlertDialog info=new AlertDialog.Builder(MainActivity.this).setTitle("豆瓣评分来源 · "+ratingDate).setMessage(ratingInfo).setPositiveButton("知道了",null).show();TvStyle.button(info.getButton(-1));}}).setPositiveButton("知道了",null).show();TvStyle.button(dialog.getButton(-1));TvStyle.button(dialog.getButton(-3));}return true;}
        return super.onKeyDown(key,event);
    }
    @Override public void onBackPressed(){generation++;if(!back.isEmpty()){show(back.pop(),false);status.setText("返回上一级 · 菜单查看说明");}else super.onBackPressed();}
    @Override public void onResume(){super.onResume();if(current!=null){if(current.tab==HISTORY){renderHistory(false);return;}int removed=current.prune();if(removed>0){show(current,false);status.setText("已自动移除 "+removed+" 个确认失效的404/410地址，请选其他线路或剧集。");}}}
    @Override public void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}
