package com.qianq.kandongman;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Runs the real TV layout and remote navigation, without contacting playback sources. */
public final class LayoutTest extends Instrumentation {
    private Activity activity;
    private StringBuilder report=new StringBuilder();
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);report.append("PASS: ").append(message).append('\n');}
    private void idle(){waitForIdleSync();SystemClock.sleep(200);waitForIdleSync();}
    private void clickTab(final int tab)throws Exception{final ArrayList<Button> buttons=(ArrayList<Button>)value(activity,"tabButtons");runOnMainSync(new Runnable(){public void run(){buttons.get(tab).performClick();}});idle();}
    private void alphabetNavigation()throws Exception{
        final ArrayList<Button> letters=(ArrayList<Button>)value(activity,"letterButtons"),tabs=(ArrayList<Button>)value(activity,"tabButtons");
        runOnMainSync(()->tabs.get(1).requestFocus());idle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();check(activity.getCurrentFocus()==letters.get(0),"catalog tab Down enters first alphabet button directly");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);idle();check(activity.getCurrentFocus()==letters.get(0),"alphabet left edge stays in alphabet");
        for(int i=1;i<14;i++){sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);idle();check(activity.getCurrentFocus()==letters.get(i),"first alphabet row Right reaches column "+i);}
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);idle();check(activity.getCurrentFocus()==letters.get(13),"alphabet right edge does not escape");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();check(activity.getCurrentFocus()==letters.get(27),"Down preserves column into second alphabet row");
        for(int i=26;i>=14;i--){sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);idle();check(activity.getCurrentFocus()==letters.get(i),"second alphabet row Left reaches column "+(i-14));}
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);idle();check(activity.getCurrentFocus()==letters.get(0),"Up returns to matching first-row column");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);idle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);idle();SystemClock.sleep(1200);idle();
        check(((Character)value(value(activity,"current"),"letter")).charValue()=='B',"Confirm filters catalog to selected letter B");
        check(activity.getCurrentFocus()==letters.get(2),"async filter and poster updates preserve selected alphabet focus");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);idle();check(activity.getCurrentFocus()==tabs.get(1),"first alphabet row Up returns to selected tab");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();check(activity.getCurrentFocus()==letters.get(2),"tab Down remembers last alphabet focus");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();check(activity.getCurrentFocus()==letters.get(16),"Down from B reaches P in matching column");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();check(Integer.valueOf(0).equals(activity.getCurrentFocus().getTag()),"second alphabet row Down enters filtered poster results");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);idle();check(activity.getCurrentFocus()==letters.get(16),"first poster Up returns to alphabet without detour");
        clickTab(1);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Intent intent=new Intent(Intent.ACTION_MAIN).setClassName("com.qianq.kandongman","com.qianq.kandongman.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=startActivitySync(intent);idle();
        final ListView list=(ListView)value(activity,"list");
        int columns=(Integer)value(activity,"posterColumns"),height=(Integer)value(activity,"posterHeight");
        check(columns>=4&&columns<=10,"poster columns adapt to screen width: "+columns);
        check(height<=176,"poster height reduced to at most 176dp: "+height);
        check(list.getHeaderViewsCount()==1&&list.getFooterViewsCount()==1,"header, content and footer share one scrolling list");
        check(list.getAdapter().getCount()==(464+columns-1)/columns+2,"all 464 posters grouped into virtualized rows");
        check(list.getChildCount()<list.getAdapter().getCount(),"only visible rows are attached");
        // Initial focus is the first real poster, not a partially clipped tall grid row.
        View focused=activity.getCurrentFocus();
        check(focused!=null&&Integer.valueOf(0).equals(focused.getTag()),"initial remote focus is first poster ("+(focused==null?"null":focused.getClass().getSimpleName()+" tag="+focused.getTag())+", touch="+list.isInTouchMode()+", first="+list.getFirstVisiblePosition()+")");
        android.graphics.Rect rect=new android.graphics.Rect();focused.getGlobalVisibleRect(rect);
        check(rect.height()>=focused.getHeight()-2,"initial poster and title fit fully on screen");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);idle();
        check((Integer)value(value(activity,"current"),"position")==1,"remote Right selects adjacent poster");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);idle();
        check((Integer)value(value(activity,"current"),"position")==0,"remote Left returns to first poster");
        for(int i=0;i<8;i++){sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();}
        Object screen=value(activity,"current");int position=(Integer)value(screen,"position");
        check(position>=columns*4,"remote Down reaches later poster rows: "+position);
        check(list.getFirstVisiblePosition()>0,"remote Down scrolls the page header out of view");
        clickTab(3);clickTab(0);check((Integer)value(value(activity,"current"),"position")>=columns*4,"switching tabs restores poster browsing position");
        for(int i=0;i<20;i++){sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);idle();}
        check(list.getFirstVisiblePosition()==0,"remote Up returns to page controls");
        runOnMainSync(new Runnable(){public void run(){try{((ArrayList<Button>)value(activity,"tabButtons")).get(0).requestFocus();}catch(Exception e){throw new RuntimeException(e);}}});idle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();
        check(activity.getCurrentFocus()!=null&&Integer.valueOf(0).equals(activity.getCurrentFocus().getTag()),"remote Down returns from top tab to first poster");
        clickTab(1);
        check((Boolean)value(value(activity,"current"),"posters"),"full catalog and classic pages use poster cards");
        alphabetNavigation();
        // A synthetic search result checks episode/list download offsets without a source request.
        Class<?> screenType=Class.forName("com.qianq.kandongman.MainActivity$Screen");Constructor<?> constructor=screenType.getDeclaredConstructor(String.class);constructor.setAccessible(true);final Object catalog=constructor.newInstance("Fixture results");
        Method add=screenType.getDeclaredMethod("add",String.class,Runnable.class,String.class);add.setAccessible(true);final int[] opened={0},downloaded={0};add.invoke(catalog,"fixture",(Runnable)()->opened[0]++,"");
        final ArrayList<Runnable> downloads=(ArrayList<Runnable>)value(catalog,"downloads");downloads.set(0,()->downloaded[0]++);
        Method show=MainActivity.class.getDeclaredMethod("show",screenType,boolean.class);show.setAccessible(true);runOnMainSync(()->{try{show.invoke(activity,catalog,true);}catch(Exception e){throw new RuntimeException(e);}});idle();
        runOnMainSync(new Runnable(){public void run(){list.requestFocus();list.setSelectionFromTop(1,0);}});idle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);idle();
        check(downloaded[0]==1&&opened[0]==0,"Right then Confirm invokes first item's download with header offset");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);idle();
        check(opened[0]==1,"Confirm invokes first catalog item with header offset");
        runOnMainSync(()->activity.onBackPressed());idle();
        final Method lastPoster=MainActivity.class.getDeclaredMethod("focusPoster",int.class);lastPoster.setAccessible(true);runOnMainSync(()->{try{lastPoster.invoke(activity,463);}catch(Exception e){throw new RuntimeException(e);}});idle();
        check((Integer)value(value(activity,"current"),"position")==463,"continuous wall reaches final title beyond original 90 posters");
        check(list.getLastVisiblePosition()==list.getCount()-1,"footer is reachable at end of long catalog");
        final EditText query=(EditText)value(activity,"query");runOnMainSync(new Runnable(){public void run(){query.setText("hzw");query.setText("mztkn");}});idle();
        ArrayList<String> filtered=(ArrayList<String>)value(value(activity,"current"),"titles");
        long filterDeadline=SystemClock.elapsedRealtime()+5000;while((filtered.size()!=1||!filtered.get(0).equals("名侦探柯南"))&&SystemClock.elapsedRealtime()<filterDeadline){idle();filtered=(ArrayList<String>)value(value(activity,"current"),"titles");}
        check(filtered.size()==1&&filtered.get(0).equals("名侦探柯南"),"latest asynchronous catalog filter wins during rapid typing (count="+filtered.size()+", query="+query.getText()+")");
        clickTab(2);check((Integer)value(activity,"tab")==2,"history tab remains accessible");
        clickTab(3);check((Integer)value(activity,"tab")==3,"classic tab remains accessible");
        clickTab(0);idle();
        final android.content.SharedPreferences prefs=activity.getSharedPreferences("anime",0);final String savedPins=prefs.getString("pinnedPosters","[]");
        try{
            runOnMainSync(()->prefs.edit().putString("pinnedPosters","[]").commit());
            final Method pin=MainActivity.class.getDeclaredMethod("togglePin",String.class);pin.setAccessible(true);runOnMainSync(()->{try{pin.invoke(activity,"正气大侠");}catch(Exception e){throw new RuntimeException(e);}});idle();
            check(((ArrayList<String>)value(value(activity,"current"),"titles")).get(0).equals("正气大侠"),"pin moves selected artwork to first position");
            activity=startActivitySync(intent);idle();
            check(((ArrayList<String>)value(value(activity,"current"),"titles")).get(0).equals("正气大侠"),"pin preference persists across activity restart");
            runOnMainSync(()->{try{pin.invoke(activity,"正气大侠");}catch(Exception e){throw new RuntimeException(e);}});idle();
            check(!PosterCatalog.pins(prefs.getString("pinnedPosters","[]")).contains("正气大侠"),"unpin removes persistent priority");
        }finally{runOnMainSync(()->prefs.edit().putString("pinnedPosters",savedPins).commit());}
        report.append(PosterCacheTest.run(this,getTargetContext()));
        result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",report.toString()+"FAIL: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
