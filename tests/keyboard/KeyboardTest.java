package com.qianq.kandongman;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;
import java.io.*;import java.lang.reflect.*;import java.util.*;

/** Actual IME service state on API21 and modern TV boxes, not just view focus. */
public final class KeyboardTest extends Instrumentation {
    Activity activity,downloads;StringBuilder report=new StringBuilder();
    Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void check(boolean ok,String name)throws Exception{if(!ok)throw new Exception(name);report.append("PASS: "+name+"\n");}
    void idle(){waitForIdleSync();SystemClock.sleep(300);waitForIdleSync();}
    boolean shown()throws Exception{try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("dumpsys input_method");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),"UTF-8").contains("mInputShown=true");}}
    void waitIme(boolean expected,String name)throws Exception{long until=SystemClock.elapsedRealtime()+5000;while(shown()!=expected&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);check(shown()==expected,name);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();SystemClock.sleep(1200);
        final EditText query=(EditText)field("query");final ArrayList<Button> tabs=(ArrayList<Button>)field("tabButtons");
        check(!shown(),"cold launch does not show keyboard");check(!query.hasFocus(),"initial navigation focus stays outside search editor");
        runOnMainSync(new Runnable(){public void run(){query.requestFocus();}});idle();check(!shown(),"focusing search with D-pad does not auto-open keyboard");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);idle();waitIme(true,"Confirm on focused search explicitly opens keyboard");
        runOnMainSync(new Runnable(){public void run(){query.setText("h");query.setSelection(query.length());}});idle();SystemClock.sleep(900);
        check(query.hasFocus()&&shown(),"asynchronous filtering keeps editor focus and keyboard while typing");
        runOnMainSync(new Runnable(){public void run(){query.onEditorAction(EditorInfo.IME_ACTION_SEARCH);}});idle();waitIme(false,"submitting filtered search hides keyboard");
        runOnMainSync(new Runnable(){public void run(){query.requestFocus();}});idle();check(!shown(),"returning navigation focus to editor keeps keyboard closed");
        sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);idle();waitIme(true,"Enter explicitly starts text input");
        setInTouchMode(false);runOnMainSync(new Runnable(){public void run(){tabs.get(1).requestFocus();tabs.get(1).performClick();}});idle();waitIme(false,"switching category after keyboard input dismisses keyboard");
        final int[] location=new int[2];runOnMainSync(new Runnable(){public void run(){query.getLocationOnScreen(location);}});long at=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(at,at,MotionEvent.ACTION_DOWN,location[0]+query.getWidth()/2,location[1]+query.getHeight()/2,0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(down);down.recycle();
        MotionEvent up=MotionEvent.obtain(at,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,location[0]+query.getWidth()/2,location[1]+query.getHeight()/2,0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(up);up.recycle();idle();waitIme(true,"actual pointer click opens keyboard");
        setInTouchMode(false);runOnMainSync(new Runnable(){public void run(){tabs.get(1).requestFocus();tabs.get(1).performClick();}});idle();waitIme(false,"switching tab after pointer input dismisses keyboard");setInTouchMode(false);
        runOnMainSync(new Runnable(){public void run(){query.performClick();}});idle();waitIme(true,"clicking search explicitly opens keyboard");
        downloads=startActivitySync(new Intent(getTargetContext(),DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();waitIme(false,"opening download manager hides keyboard");
        runOnMainSync(new Runnable(){public void run(){downloads.finish();}});downloads=null;idle();SystemClock.sleep(500);waitIme(false,"return from another screen does not reopen keyboard");
        setInTouchMode(false);runOnMainSync(new Runnable(){public void run(){tabs.get(1).requestFocus();tabs.get(1).performClick();}});idle();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();
        check(activity.getCurrentFocus().getTag() instanceof Character&&!shown(),"catalog alphabet navigation works without keyboard");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);idle();
        check(activity.getCurrentFocus().getTag() instanceof Integer&&!shown(),"poster navigation works without keyboard");
        runOnMainSync(new Runnable(){public void run(){query.performClick();}});idle();waitIme(true,"keyboard can reopen before Back test");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);idle();waitIme(false,"first Back closes keyboard");
        check(!activity.isFinishing()&&!activity.isDestroyed(),"first Back preserves current page");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);idle();check(activity.isFinishing()||activity.isDestroyed(),"second Back performs normal page return");
        result.putString("stream",report.toString());
    }catch(Throwable error){String diagnostic="";try{diagnostic=" destroyed="+activity.isDestroyed()+" finishing="+activity.isFinishing()+" input="+field("searchInputRequested")+" opening="+field("openingSearchInput")+" focus="+activity.getCurrentFocus()+" selected="+((ListView)field("list")).getSelectedItemPosition()+" touch="+((ListView)field("list")).isInTouchMode();}catch(Exception ignored){}result.putString("stream",report+"ERROR: "+android.util.Log.getStackTraceString(error)+diagnostic);}
    finally{if(downloads!=null)runOnMainSync(new Runnable(){public void run(){downloads.finish();}});if(activity!=null)runOnMainSync(new Runnable(){public void run(){activity.finish();}});}
    finish(result.getString("stream","").contains("ERROR:")?1:-1,result);}
}
