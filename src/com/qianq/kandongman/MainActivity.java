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
    private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor();
    private Future<?> catalogWork;
    private PosterCatalog posterCatalog;
    private PosterLoader posterLoader;
    private final LinkedHashMap<String,int[]> browsePositions=new LinkedHashMap<String,int[]>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,int[]> e){return size()>16;}};
    private final ArrayDeque<Screen> back=new ArrayDeque<Screen>();
    private ArrayList<Sources.Source> sources;
    private Sources.Source source;
    private Screen current;
    private TextView status,heading;
    private EditText query;
    private ListView list;
    private LinearLayout pageHeader;
    private int posterColumns,posterHeight;
    private int sortMode=0;
    private int downloadSelection=-1;
    private AlertDialog downloadDialog;
    private ProgressDialog taskProgress;
    private AlertDialog taskError;
    private Future<?> taskWork;
    private Spinner sourcePicker;
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> labels=new ArrayList<String>();
    private SharedPreferences prefs;
    private int generation;
    private final ArrayList<AnimeCatalog.Entry> catalog=AnimeCatalog.load();
    private char letter='*';
    private boolean settingQuery;
    private boolean searchInputRequested,openingSearchInput;
    private final Runnable hideUnfocusedSearch=new Runnable(){public void run(){if(query!=null&&!query.hasFocus()&&!openingSearchInput)hideSearchKeyboard();}};
    private int hiddenTitles;
    private LinearLayout alphabet;
    private final ArrayList<Button> letterButtons=new ArrayList<Button>();
    private int letterFocusIndex;
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
        final ArrayList<String> titles=new ArrayList<String>();
        int position;
        boolean catalog,posters;
        String cacheKey="";
        Sources.Source posterSource;
        int tab=-1;
        String filter="";
        char letter='*';
        Screen(String title){this.title=title;}
        void add(String name,Runnable action){add(name,action,"");}
        void add(String name,Runnable action,String url){names.add(name);actions.add(action);links.add(url);covers.add("");titles.add("");downloads.add(null);}
        int prune(){int count=0;for(int i=links.size()-1;i>=0;i--)if(LinkHealth.isBlocked(links.get(i))){links.remove(i);covers.remove(i);titles.remove(i);downloads.remove(i);names.remove(i);actions.remove(i);count++;}position=Math.max(0,Math.min(position,names.size()-1));return count;}
    }
    private interface Work {Screen run() throws Exception;}
    private static final class PosterRow {
        final Screen screen;final int position;
        PosterRow(Screen screen,int position){this.screen=screen;this.position=position;}
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(1024,1024);
        prefs=getSharedPreferences("anime",MODE_PRIVATE);sortMode=prefs.getInt("catalogSort",0);
        posterLoader=new PosterLoader(this);
        // One virtualized list owns the entire page, including the controls and footer.
        // A fixed header plus a tall GridView item leaves too little room on 540dp TVs.
        posterColumns=Math.max(4,Math.min(10,(getResources().getDisplayMetrics().widthPixels-dp(48))/dp(148)));
        posterHeight=Math.max(120,Math.min(176,(int)(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density*.32f)));
        list=new ListView(this){@Override public boolean dispatchKeyEvent(KeyEvent event){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&navigateAlphabet(event.getKeyCode()))return true;
            if(event.getAction()==KeyEvent.ACTION_DOWN&&current!=null&&current.posters){
                View focused=getCurrentFocus();int key=event.getKeyCode();
                if(focused!=null&&focused.getTag() instanceof Integer){
                    int item=((Integer)focused.getTag()).intValue(),target=item;
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(item/posterColumns==(current.names.size()-1)/posterColumns)return true;target=Math.min(current.names.size()-1,item+posterColumns);}
                    else if(key==KeyEvent.KEYCODE_DPAD_UP){if(item<posterColumns){focusHeader(current.catalog?letterButtons.get(letterFocusIndex):tabButtons.get(Math.max(0,tab)));return true;}target=item-posterColumns;}
                    else if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(item%posterColumns>0)target=item-1;}
                    else if(key==KeyEvent.KEYCODE_DPAD_RIGHT){if(item%posterColumns<posterColumns-1)target=Math.min(current.names.size()-1,item+1);}
                    else return super.dispatchKeyEvent(event);
                    focusPoster(target);return true;
                }
                if(key==KeyEvent.KEYCODE_DPAD_DOWN&&tabButtons.contains(focused)){if(current.catalog)focusHeader(letterButtons.get(letterFocusIndex));else focusPoster(current.position);return true;}
            }
            return super.dispatchKeyEvent(event);
        }};list.setBackgroundColor(0xFF151727);list.setPadding(dp(24),dp(14),dp(24),dp(14));list.setClipToPadding(false);list.setItemsCanFocus(true);list.setDivider(null);list.setSelector(TvStyle.rows(this));list.setDrawSelectorOnTop(false);list.setSmoothScrollbarEnabled(true);setContentView(list);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xFF151727);pageHeader=root;list.addHeaderView(root,null,true);
        TextView brand=text("千秋动画",30,0xFFE1D5FF);brand.setTypeface(null,Typeface.BOLD);root.addView(brand);
        TextView guide=text("全部海报 · 向下持续浏览 · 菜单 / 长按海报置顶",16,0xFFB7B6C9);guide.setPadding(0,dp(5),0,dp(8));root.addView(guide);
        LinearLayout bar=new LinearLayout(this);
        query=new EditText(this){@Override public boolean onKeyPreIme(int key,KeyEvent event){
            if(key==KeyEvent.KEYCODE_BACK&&searchInputRequested){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())hideSearchKeyboard();return true;}
            if((key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER)&&!searchInputRequested){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())openSearchKeyboard();return true;}
            return super.onKeyPreIme(key,event);
        }};query.setSingleLine();query.setTextSize(20);query.setTextColor(Color.WHITE);query.setHintTextColor(0xFFAAA9BD);query.setHint("名称或首字母，如 mztkn / hzw");query.setImeOptions(3);
        // D-pad focus is navigation; only a click/Confirm explicitly opens the IME.
        query.setShowSoftInputOnFocus(false);
        query.setOnTouchListener(new View.OnTouchListener(){public boolean onTouch(View v,MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_UP)openSearchKeyboard();return false;}});
        query.setOnClickListener(new View.OnClickListener(){public void onClick(View v){openSearchKeyboard();}});
        query.setOnFocusChangeListener(new View.OnFocusChangeListener(){public void onFocusChange(View v,boolean focused){ui.removeCallbacks(hideUnfocusedSearch);if(!focused&&!openingSearchInput)ui.postDelayed(hideUnfocusedSearch,150);}});
        query.setOnKeyListener(new View.OnKeyListener(){public boolean onKey(View v,int key,KeyEvent event){
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||(key==KeyEvent.KEYCODE_ENTER&&!searchInputRequested)){
                if(event.getAction()==KeyEvent.ACTION_UP)openSearchKeyboard();return true;
            }return false;
        }});
        bar.addView(query,new LinearLayout.LayoutParams(0,dp(50),1));
        button(bar,"搜索",new Runnable(){public void run(){search(query.getText().toString());}});
        button(bar,"目录",new Runnable(){public void run(){selectTab(ALL,true);}});
        final Spinner picker=new Spinner(this);sourcePicker=picker;TvStyle.focusable(picker);bar.addView(picker,new LinearLayout.LayoutParams(dp(190),dp(50)));
        button(bar,"继续观看",new Runnable(){public void run(){resume();}});
        button(bar,"排序",new Runnable(){public void run(){AlertDialog d=new AlertDialog.Builder(MainActivity.this).setTitle("排序方式").setItems(new String[]{"热门评分从高到低 / 目录 A–Z","作品名称 A–Z","作品名称 Z–A"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){sortMode=which;prefs.edit().putInt("catalogSort",which).apply();renderHome(true);}}).setNegativeButton("返回",null).show();TvStyle.dialog(d);}});
        button(bar,"下载管理",new Runnable(){public void run(){startActivity(new Intent(MainActivity.this,DownloadsActivity.class));}});
        root.addView(bar);
        LinearLayout tabs=new LinearLayout(this);root.addView(tabs);
        String[] tabNames={"全部海报","全部动漫","历史","经典怀旧"};
        for(int i=0;i<tabNames.length;i++){
            final int target=i;Button b=new Button(this);b.setText(tabNames[i]);b.setTextSize(19);TvStyle.button(b);
            b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){selectTab(target,false);}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);lp.setMargins(dp(3),dp(6),dp(3),dp(6));tabs.addView(b,lp);tabButtons.add(b);
        }
        tabButtons.get(HOT).requestFocus();
        alphabet=new LinearLayout(this);alphabet.setOrientation(1);root.addView(alphabet);
        String[] letters={"全部","A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z","#"};
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(this);alphabet.addView(line);
            for(int col=0;col<14;col++){
                final String name=letters[row*14+col];final char value=name.equals("全部")?'*':name.charAt(0);
                Button key=new Button(this);key.setText(name);key.setTextSize(16);TvStyle.button(key);key.setPadding(0,0,0,0);key.setTag(Character.valueOf(value));
                final int letterIndex=row*14+col;key.setOnFocusChangeListener(new View.OnFocusChangeListener(){public void onFocusChange(View v,boolean focused){if(focused)letterFocusIndex=letterIndex;}});
                key.setOnClickListener(new View.OnClickListener(){public void onClick(View v){generation++;letter=value;setQuery("");renderCatalog(false);}});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(36),1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));line.addView(key,lp);letterButtons.add(key);
            }
        }
        heading=text("推荐搜索",21,0xFFF5DE9A);heading.setPadding(0,dp(14),0,dp(8));root.addView(heading);
        adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public int getCount(){return current!=null&&current.posters?(labels.size()+posterColumns-1)/posterColumns:labels.size();}
            @Override public int getViewTypeCount(){return 2;}
            @Override public int getItemViewType(int position){return current!=null&&current.posters?1:0;}
            @Override public View getView(int position,View convert,ViewGroup parent){
                if(current!=null&&current.posters){
                    // Relayout must retain the actual focused card, not replace its View.
                    if(convert!=null&&convert.getTag() instanceof PosterRow){PosterRow bound=(PosterRow)convert.getTag();if(bound.screen==current&&bound.position==position)return convert;}
                    LinearLayout row=convert instanceof LinearLayout?(LinearLayout)convert:new LinearLayout(MainActivity.this);row.removeAllViews();row.setTag(new PosterRow(current,position));row.setPadding(0,0,0,dp(8));row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    final Screen screen=current;
                    for(int column=0;column<posterColumns;column++){
                        final int item=position*posterColumns+column;
                        LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-2,1);cell.setMargins(dp(3),0,dp(3),0);
                        if(item>=screen.names.size()){row.addView(new View(MainActivity.this),cell);continue;}
                        LinearLayout card=new LinearLayout(MainActivity.this);card.setOrientation(1);card.setPadding(dp(5),dp(5),dp(5),dp(6));card.setTag(Integer.valueOf(item));card.setFocusableInTouchMode(true);card.setClickable(true);card.setBackground(TvStyle.background(MainActivity.this));
                        FrameLayout artwork=new FrameLayout(MainActivity.this);artwork.setBackgroundColor(0xFF202638);card.addView(artwork,new LinearLayout.LayoutParams(-1,dp(posterHeight)));
                        ImageView image=new ImageView(MainActivity.this);image.setFocusable(false);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(screen.titles.get(item));artwork.addView(image,new FrameLayout.LayoutParams(-1,-1));
                        TextView placeholder=text(screen.titles.get(item),18,0xFFBFC9E3);placeholder.setGravity(Gravity.CENTER);placeholder.setPadding(dp(8),dp(8),dp(8),dp(8));artwork.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));posterLoader.bind(image,placeholder,screen.posterSource,screen.titles.get(item),screen.covers.get(item),dp(posterHeight));
                        TextView title=text(screen.names.get(item),14,Color.WHITE);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setMinHeight(dp(44));title.setPadding(dp(3),dp(4),dp(3),0);card.addView(title);
                        final TextView caption=title;card.setOnFocusChangeListener(new View.OnFocusChangeListener(){public void onFocusChange(View v,boolean focused){caption.setTextColor(focused?0xFF14182A:Color.WHITE);if(focused&&current==screen)screen.position=item;}});
                        card.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(current==screen){screen.position=item;screen.actions.get(item).run();}}});
                        card.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){if(current==screen){screen.position=item;posterActions(screen,item);}return true;}});
                        row.addView(card,cell);
                    }
                    return row;
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
        status=text("正在准备…",15,0xFFBBBCCF);status.setMaxLines(3);status.setPadding(0,dp(8),0,dp(8));list.addFooterView(status,null,false);list.setAdapter(adapter);
        list.setOnKeyListener(new View.OnKeyListener(){public boolean onKey(View v,int key,KeyEvent event){if(event.getAction()!=KeyEvent.ACTION_DOWN||current==null||current.posters)return false;int selected=list.getSelectedItemPosition()-list.getHeaderViewsCount();if(selected<0||selected>=current.downloads.size()||current.downloads.get(selected)==null)return false;if(key==KeyEvent.KEYCODE_DPAD_RIGHT){downloadSelection=selected;adapter.notifyDataSetChanged();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT&&downloadSelection>=0){downloadSelection=-1;adapter.notifyDataSetChanged();return true;}if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&downloadSelection==selected){current.downloads.get(selected).run();return true;}if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){downloadSelection=-1;adapter.notifyDataSetChanged();}return false;}});
        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int pos,long id){int item=pos-list.getHeaderViewsCount();if(current!=null&&!current.posters&&item>=0&&item<current.actions.size()){current.position=item;current.actions.get(item).run();}}});
        list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int state){if(state==SCROLL_STATE_IDLE)updatePosterWindow();}public void onScroll(AbsListView view,int first,int count,int total){updatePosterWindow();}});
        query.setOnEditorActionListener(new TextView.OnEditorActionListener(){public boolean onEditorAction(TextView v,int action,android.view.KeyEvent event){if(action==3||action==6||(event!=null&&event.getKeyCode()==66&&event.getAction()==1)){search(query.getText().toString());return true;}return false;}});
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){if(!settingQuery&&source!=null){generation++;letter='*';renderCatalog(false);}} public void afterTextChanged(Editable s){}});
        try {
            sources=Sources.load(this);
            try(java.io.InputStream in=getAssets().open("douban-popular.json")){JSONObject data=new JSONObject(Sources.read(in,1024*1024));popular=data.getJSONArray("items");ratingDate=data.getString("date");ratingInfo=data.getString("method")+"\n\n采集日期："+ratingDate+"\n\n";JSONArray urls=data.getJSONArray("sources");for(int i=0;i<urls.length();i++)ratingInfo+=urls.getString(i)+"\n\n";}
            posterCatalog=new PosterCatalog(popular);
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
    private void updatePosterWindow(){
        if(posterLoader==null||current==null||!current.posters){if(posterLoader!=null)posterLoader.retain(Collections.<String>emptySet());return;}
        Screen screen=current;int first=Math.max(0,list.getFirstVisiblePosition()-list.getHeaderViewsCount())*posterColumns;
        int last=Math.min(screen.names.size(),(Math.max(0,list.getLastVisiblePosition()-list.getHeaderViewsCount())+3)*posterColumns);
        HashSet<String> keep=new HashSet<String>();for(int i=first;i<last;i++)keep.add(PosterLoader.key(screen.posterSource,screen.titles.get(i),screen.covers.get(i),dp(posterHeight)));posterLoader.retain(keep);
        // Rebind visible views after pruning a stale queue; tags prevent recycled-image races.
        for(int i=0;i<list.getChildCount();i++){View row=list.getChildAt(i);if(!(row.getTag() instanceof PosterRow)||((PosterRow)row.getTag()).screen!=screen)continue;ViewGroup cells=(ViewGroup)row;
            for(int j=0;j<cells.getChildCount();j++){View cell=cells.getChildAt(j);if(!(cell.getTag() instanceof Integer))continue;int item=((Integer)cell.getTag()).intValue();FrameLayout artwork=(FrameLayout)((ViewGroup)cell).getChildAt(0);posterLoader.bind((ImageView)artwork.getChildAt(0),(TextView)artwork.getChildAt(1),screen.posterSource,screen.titles.get(item),screen.covers.get(item),dp(posterHeight));}
        }
        for(int i=first;i<last;i++)posterLoader.prefetch(screen.posterSource,screen.titles.get(i),screen.covers.get(i),dp(posterHeight));
    }
    private int selectedItem(){if(current==null)return 0;if(current.posters)return current.position;int item=list.getSelectedItemPosition()-list.getHeaderViewsCount();return item>=0&&item<current.names.size()?item:current.position;}
    private void rememberScreen(){if(current==null||current.cacheKey.isEmpty())return;current.position=selectedItem();if(current.posters&&list.isInTouchMode())current.position=Math.min(Math.max(0,current.names.size()-1),Math.max(0,list.getFirstVisiblePosition()-list.getHeaderViewsCount())*posterColumns);View first=list.getChildAt(0);browsePositions.put(current.cacheKey,new int[]{current.position,list.getFirstVisiblePosition(),first==null?0:first.getTop()});}
    private View findPoster(int item){for(int i=0;i<list.getChildCount();i++){View row=list.getChildAt(i);if(row.getTag() instanceof PosterRow&&((PosterRow)row.getTag()).screen==current)for(int j=0;j<((ViewGroup)row).getChildCount();j++){View card=((ViewGroup)row).getChildAt(j);if(Integer.valueOf(item).equals(card.getTag()))return card;}}return null;}
    private void focusAfterLayout(final Screen screen,final int item){
        if(item<0||item>=screen.names.size())return;
        list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
            if(current!=screen||isDestroyed()){list.getViewTreeObserver().removeOnGlobalLayoutListener(this);return;}
            if(searchInputRequested&&query.hasFocus()){list.getViewTreeObserver().removeOnGlobalLayoutListener(this);return;}
            View card=findPoster(item);if(card!=null){
                int row=item/posterColumns+list.getHeaderViewsCount();
                // Keep ListView's selected row aligned with its focused child. Otherwise an
                // asynchronous image relayout restores selection to the header's EditText.
                if(!list.isInTouchMode()&&list.getSelectedItemPosition()!=row){list.setSelectionFromTop(row,((View)card.getParent()).getTop()-list.getPaddingTop());return;}
                list.getViewTreeObserver().removeOnGlobalLayoutListener(this);card.requestFocus();screen.position=item;
            }
        }});list.requestLayout();
    }
    private void focusHeader(final View target){
        list.setSelectionFromTop(0,0);
        list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
            list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            if(!isDestroyed()&&target.isShown())target.requestFocus();
        }});list.requestLayout();
    }
    private boolean navigateAlphabet(int key){
        if(current==null||!current.catalog||alphabet.getVisibility()!=View.VISIBLE)return false;
        int index=letterButtons.indexOf(getCurrentFocus());if(index<0)return false;
        int target=index;
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(index%14>0)target--;}
        else if(key==KeyEvent.KEYCODE_DPAD_RIGHT){if(index%14<13)target++;}
        else if(key==KeyEvent.KEYCODE_DPAD_UP){if(index<14){focusHeader(tabButtons.get(tab));return true;}target-=14;}
        else if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(index>=14){focusPoster(current.position);return true;}target+=14;}
        else return false;
        letterButtons.get(target).requestFocus();return true;
    }
    private void focusPoster(final int item){
        final Screen screen=current;if(screen==null||!screen.posters||item<0||item>=screen.names.size())return;
        View visible=findPoster(item);android.graphics.Rect bounds=new android.graphics.Rect();
        if(visible!=null&&visible.getLocalVisibleRect(bounds)&&bounds.height()>=visible.getHeight()-2){int row=item/posterColumns+list.getHeaderViewsCount();if(list.isInTouchMode()||list.getSelectedItemPosition()==row){visible.requestFocus();screen.position=item;}else{list.setSelectionFromTop(row,((View)visible.getParent()).getTop()-list.getPaddingTop());focusAfterLayout(screen,item);}return;}
        list.setSelectionFromTop(item/posterColumns+list.getHeaderViewsCount(),dp(8));
        focusAfterLayout(screen,item);
    }
    private void selectItem(final Screen screen,final boolean focus){
        list.post(new Runnable(){public void run(){if(current!=screen)return;if(searchInputRequested&&query.hasFocus()){list.setSelectionFromTop(0,0);return;}int row=screen.posters?screen.position/posterColumns:screen.position;int[] saved=browsePositions.get(screen.cacheKey);
            if(!screen.posters&&focus&&!screen.names.isEmpty()){
                list.setSelectionFromTop(row+list.getHeaderViewsCount(),dp(8));
                list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
                    list.getViewTreeObserver().removeOnGlobalLayoutListener(this);if(current!=screen||isDestroyed())return;
                    // Focus the result list itself, never the search editor in its header.
                    int policy=list.getDescendantFocusability();list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);list.requestFocusFromTouch();list.setDescendantFocusability(policy);
                    list.setSelection(screen.position+list.getHeaderViewsCount());
                }});list.requestLayout();return;
            }
            if(saved!=null)list.setSelectionFromTop(saved[1],saved[2]);else if(screen.position==0)list.setSelectionFromTop(0,0);else list.setSelectionFromTop(row+list.getHeaderViewsCount(),0);
            if(focus&&!(searchInputRequested&&query.hasFocus())){list.requestFocus();if(screen.posters)focusAfterLayout(screen,screen.position);}
        }});
    }
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void button(LinearLayout bar,String title,final Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(17);TvStyle.button(b);b.setPadding(dp(14),0,dp(14),0);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(46));lp.setMargins(dp(3),dp(2),dp(3),dp(2));bar.addView(b,lp);}
    private void show(Screen screen,boolean push) {
        if(push&&current!=null){rememberScreen();current.position=selectedItem();back.push(current);}
        downloadSelection=-1;screen.prune();current=screen;heading.setText(screen.title);labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();
        if(screen.tab>=0){tab=screen.tab;updateTabs();if(!screen.catalog)setQuery("");}
        alphabet.setVisibility(screen.catalog?View.VISIBLE:View.GONE);
        if(screen.catalog){letter=screen.letter;setQuery(screen.filter);updateLetters();}
        selectItem(screen,true);
    }
    private void home() {
        renderHome(true);
    }
    private void updateTabs(){for(int i=0;i<tabButtons.size();i++)tabButtons.get(i).setActivated(i==tab);}
    private void selectTab(int target,boolean focus){hideSearchKeyboard();rememberScreen();generation++;back.clear();tab=target;letter='*';letterFocusIndex=0;setQuery("");renderHome(focus);}
    private void renderHome(boolean focus){updateTabs();if(tab==ALL)renderCatalog(focus);else if(tab==HISTORY)renderHistory(focus);else if(tab==CLASSIC)renderCatalog(focus);else renderPopular(focus);}
    private void homeScreen(Screen screen,boolean focus){downloadSelection=-1;
        final View preserved=getCurrentFocus();
        current=screen;int[] saved=browsePositions.get(screen.cacheKey);if(saved!=null)screen.position=saved[0];heading.setText(screen.title);labels.clear();labels.addAll(screen.names);adapter.notifyDataSetChanged();alphabet.setVisibility(screen.catalog?View.VISIBLE:View.GONE);updateTabs();selectItem(screen,focus);
        if(!focus&&screen.catalog&&letterButtons.contains(preserved))list.post(new Runnable(){public void run(){if(current==screen)focusHeader(preserved);}});
    }
    private void renderPopular(boolean focus){renderPosters(HOT,"",'*',focus);}
    private void renderPosters(final int scope,final String filter,final char selectedLetter,final boolean focus){
        if(posterCatalog==null||source==null)return;rememberScreen();final int token=++generation;final int sort=sortMode;final Sources.Source chosen=source;final List<String> pins=PosterCatalog.pins(prefs.getString("pinnedPosters","[]"));
        if(catalogWork!=null)catalogWork.cancel(true);
        catalogWork=catalogWorker.submit(new Runnable(){public void run(){final List<PosterCatalog.Item> items=posterCatalog.list(scope,filter,selectedLetter,sort,pins);if(Thread.currentThread().isInterrupted())return;ui.post(new Runnable(){public void run(){
            if(isDestroyed()||token!=generation)return;Screen screen=new Screen((scope==CLASSIC?"经典怀旧":scope==ALL?"全部动漫":"全部海报")+" · "+items.size()+" / "+catalog.size()+" 部 · "+(sort==2?"名称 Z–A":scope==HOT&&sort==0?"置顶 / 高分推荐 / 其余动漫":"置顶 / 名称 A–Z"));
            screen.tab=scope;screen.posters=true;screen.catalog=scope!=HOT;screen.filter=filter;screen.letter=selectedLetter;screen.posterSource=chosen;screen.cacheKey=scope+"|"+chosen.id+"|"+sort+"|"+selectedLetter+"|"+AnimeCatalog.normalize(filter)+"|"+new JSONArray(pins);
            for(final PosterCatalog.Item item:items){screen.add((pins.contains(item.title)?"★ 置顶 · ":"")+item.caption,new Runnable(){public void run(){setQuery(item.title);onlineSearch(item.title);}});int index=screen.names.size()-1;screen.titles.set(index,item.title);screen.covers.set(index,item.asset);}
            homeScreen(screen,focus);updateLetters();status.setText("共 "+items.size()+" 部 · 向下持续浏览 · 菜单 / 长按海报置顶 · 海报按需加载，无图作品保留标题入口。评分快照 "+ratingDate+"，非实时热度榜。");
        }});}});
    }
    private void renderHistory(boolean focus){
        generation++;
        Screen screen=new Screen("历史 · 最近观看");screen.tab=HISTORY;int position=current!=null&&current.tab==HISTORY?current.position:0;
        try{for(final JSONObject item:WatchHistory.list(this)){
            int index=Math.max(0,Math.min(item.getJSONArray("names").length()-1,item.optInt("index")));long seconds=item.optLong("position")/1000;
            screen.add(item.getString("title")+"  ·  "+item.getJSONArray("names").getString(index)+String.format(Locale.US,"  ·  %02d:%02d",seconds/60,seconds%60),new Runnable(){public void run(){launchHistory(item);}});
        }}catch(Exception error){status.setText("历史读取失败："+error.getMessage());}
        screen.position=position;homeScreen(screen,focus);status.setText(screen.names.isEmpty()?"还没有观看记录；从热门或全部动漫选择一部开始观看。":"最近 "+screen.names.size()+" 部 · 按最近观看时间排序 · 确定继续上次剧集和进度 · 最多保留20部");
    }
    private void setQuery(String value){settingQuery=true;query.setText(value);query.setSelection(query.length());settingQuery=false;}
    private void openSearchKeyboard(){
        if(openingSearchInput)return;
        searchInputRequested=true;openingSearchInput=true;
        // Align the selected ListView row before IME resize can restore old poster focus.
        list.setSelectionFromTop(0,0);
        list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
            list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            if(!searchInputRequested||isDestroyed()){openingSearchInput=false;return;}
            query.requestFocus();query.setShowSoftInputOnFocus(true);openingSearchInput=false;
            query.post(new Runnable(){public void run(){if(searchInputRequested&&query.hasFocus())((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}});
        }});list.requestLayout();
    }
    private void hideSearchKeyboard(){ui.removeCallbacks(hideUnfocusedSearch);searchInputRequested=false;openingSearchInput=false;if(query!=null){query.setShowSoftInputOnFocus(false);((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);}}
    private void updateLetters(){for(Button b:letterButtons){boolean selected=((Character)b.getTag()).charValue()==letter;b.setActivated(selected);b.setText(b.getTag().equals(Character.valueOf('*'))?(selected?"✓全部":"全部"):(selected?"✓":"")+b.getTag().toString());}}
    private void renderCatalog(boolean focus) {
        if(tab!=CLASSIC)tab=ALL;updateTabs();renderPosters(tab,query.getText().toString(),letter,focus);
    }
    private void togglePin(String title){List<String> pins=PosterCatalog.toggle(PosterCatalog.pins(prefs.getString("pinnedPosters","[]")),title);prefs.edit().putString("pinnedPosters",new JSONArray(pins).toString()).apply();browsePositions.clear();if(current!=null)current.cacheKey="";renderHome(true);}
    private void posterActions(final Screen screen,final int item){
        if(item<0||item>=screen.titles.size())return;final String title=screen.titles.get(item);boolean pinned=PosterCatalog.pins(prefs.getString("pinnedPosters","[]")).contains(title);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setItems(new String[]{pinned?"取消置顶":"置顶海报","搜索播放","回到页面顶部","清理海报缓存"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){if(which==0)togglePin(title);else if(which==1)screen.actions.get(item).run();else if(which==2){list.setSelectionFromTop(0,0);list.post(new Runnable(){public void run(){tabButtons.get(tab).requestFocus();}});}else showPosterCache();}}).setNegativeButton("返回",null).show();TvStyle.dialog(dialog);
    }
    private void showPosterCache(){AlertDialog d=new AlertDialog.Builder(this).setTitle("海报缓存").setMessage(posterLoader.stats()).setPositiveButton("清理海报缓存",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){posterLoader.clear(new Runnable(){public void run(){if(isDestroyed())return;updatePosterWindow();Toast.makeText(MainActivity.this,"海报缓存已清理",Toast.LENGTH_SHORT).show();}});}}).setNegativeButton("返回",null).show();TvStyle.dialog(d);}
    private void task(String message,final Work work) {
        hideSearchKeyboard();if(taskWork!=null)taskWork.cancel(true);dismissTaskDialogs();
        final int token=++generation;final String progressMessage=message;status.setText(message);
        taskProgress=new ProgressDialog(this);taskProgress.setMessage(message);taskProgress.setCancelable(true);taskProgress.setCanceledOnTouchOutside(false);
        taskProgress.setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){if(token==generation){generation++;if(taskWork!=null)taskWork.cancel(true);status.setText("已取消，可重新打开作品");}taskProgress=null;}});taskProgress.show();
        taskWork=worker.submit(new Runnable(){public void run(){
            try {final Screen screen=work.run();ui.post(new Runnable(){public void run(){if(isDestroyed()||isFinishing()||token!=generation)return;dismissTaskDialogs();if(screen.names.isEmpty()){status.setText("没有找到结果，可换来源或使用更短的关键词。");showTaskFailure("没有找到结果，可换来源或使用更短的关键词。",progressMessage,work);return;}show(screen,true);status.setText("共 "+screen.names.size()+" 项 · 确定打开 · 返回上一级");}});}
            catch(final Exception e){ui.post(new Runnable(){public void run(){if(isDestroyed()||isFinishing()||token!=generation)return;dismissTaskDialogs();if(current!=null&&current.prune()>0)show(current,false);String reason=e instanceof java.net.UnknownHostException?"来源域名暂时无法解析，请重试或更换来源。":e instanceof java.net.SocketTimeoutException?"来源响应超时，请重试或更换来源。":"未能打开："+e.getMessage();status.setText(reason);showTaskFailure(reason,progressMessage,work);}});}
        }});
    }
    private void dismissTaskDialogs(){if(taskProgress!=null){taskProgress.dismiss();taskProgress=null;}if(taskError!=null){taskError.dismiss();taskError=null;}}
    private void showTaskFailure(String reason,final String message,final Work work){
        taskError=new AlertDialog.Builder(this).setTitle("暂时无法打开").setMessage(reason)
            .setPositiveButton("重试",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){task(message,work);}})
            .setNeutralButton("更换来源",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){AlertDialog choices=new AlertDialog.Builder(MainActivity.this).setTitle("更换来源后重新选择作品").setItems(sourceNames(),new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int item){sourcePicker.setSelection(item);}}).setNegativeButton("返回",null).show();TvStyle.dialog(choices);}})
            .setNegativeButton("返回",null).show();TvStyle.dialog(taskError);taskError.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
    }
    private String[] sourceNames(){String[] names=new String[sources.size()];for(int i=0;i<names.length;i++)names[i]=sources.get(i).name;return names;}
    private void search(String keyword) {
        hideSearchKeyboard();
        String term=keyword.trim();if(source==null||term.isEmpty()){letter='*';home();return;}
        ArrayList<AnimeCatalog.Entry> matches=AnimeCatalog.filter(catalog,term,'*');
        for(AnimeCatalog.Entry entry:matches)if(AnimeCatalog.normalize(entry.title).equals(AnimeCatalog.normalize(term))||(matches.size()==1&&entry.exact(term))){setQuery(entry.title);onlineSearch(entry.title);return;}
        if(!matches.isEmpty()){letter='*';renderCatalog(true);status.setText("匹配 "+matches.size()+" 部动漫；选中中文名称按确定，查询播放来源。");return;}
        onlineSearch(term);
    }
    private void onlineSearch(final String term) {
        prefs.edit().putString("query",term).apply();
        hideSearchKeyboard();
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
        if(key==KeyEvent.KEYCODE_MENU&&current!=null&&current.posters){View focus=getCurrentFocus();if(focus!=null&&focus.getTag() instanceof Integer){if(event.getRepeatCount()==0)posterActions(current,((Integer)focus.getTag()).intValue());return true;}}
        if(key==KeyEvent.KEYCODE_MENU){if(event.getRepeatCount()==0){AlertDialog dialog=new AlertDialog.Builder(this).setTitle("千秋动画 1.6.6").setMessage("面向遥控器的动漫搜索与播放器。\n\n热门按豆瓣评分降序，显示具体作品/季和快照日期。\n历史保留最近20部及播放进度。\n\n来源规则：KazumiRules（MIT）。\nHTML解析：jsoup（MIT）。\n播放器：VideoLAN LibVLC 3.6.5（LGPL 2.1）。\n应用源码及许可证随安装包交付。\n\n来源页面和线路可能变化；搜索结果不等于每条播放线路都可用。").setNeutralButton("评分来源",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){AlertDialog info=new AlertDialog.Builder(MainActivity.this).setTitle("豆瓣评分来源 · "+ratingDate).setMessage(ratingInfo).setPositiveButton("知道了",null).show();TvStyle.button(info.getButton(-1));}}).setPositiveButton("知道了",null).show();TvStyle.button(dialog.getButton(-1));TvStyle.button(dialog.getButton(-3));}return true;}
        return super.onKeyDown(key,event);
    }
    @Override public void onBackPressed(){if(searchInputRequested){hideSearchKeyboard();return;}generation++;if(!back.isEmpty()){show(back.pop(),false);status.setText("返回上一级 · 菜单查看说明");}else super.onBackPressed();}
    @Override public void onResume(){super.onResume();searchInputRequested=false;query.post(new Runnable(){public void run(){if(!searchInputRequested)hideSearchKeyboard();}});if(current!=null){if(current.tab==HISTORY){renderHistory(false);return;}int removed=current.prune();if(removed>0){show(current,false);status.setText("已自动移除 "+removed+" 个确认失效的404/410地址，请选其他线路或剧集。");}list.post(new Runnable(){public void run(){updatePosterWindow();}});}}
    @Override protected void onStop(){hideSearchKeyboard();if(taskProgress!=null){generation++;if(taskWork!=null)taskWork.cancel(true);}dismissTaskDialogs();rememberScreen();if(posterLoader!=null)posterLoader.retain(Collections.<String>emptySet());super.onStop();}
    @Override public void onDestroy(){generation++;worker.shutdownNow();catalogWorker.shutdownNow();if(posterLoader!=null)posterLoader.close();super.onDestroy();}
}
