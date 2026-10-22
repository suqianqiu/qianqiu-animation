package com.qianq.kandongman;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.Button;

/** Explicit TV states, independent of the OEM's low-contrast Material theme. */
final class TvStyle {
    private static int dp(Context c,int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    private static GradientDrawable shape(Context c,int fill,int border,int width){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,7));d.setStroke(dp(c,width),border);return d;}
    static StateListDrawable background(Context c){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed},shape(c,0xFFFFC83D,Color.WHITE,3));
        d.addState(new int[]{android.R.attr.state_focused},shape(c,0xFFFFE082,Color.WHITE,3));
        d.addState(new int[]{android.R.attr.state_activated},shape(c,0xFF6352C7,0xFFC9BEFF,2));
        d.addState(new int[]{},shape(c,0xFF2D344C,0xFF56617F,1));return d;
    }
    static void button(Button b){
        b.setBackground(background(b.getContext()));b.setStateListAnimator(null);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},new int[]{}},new int[]{0xFF14182A,0xFF14182A,Color.WHITE}));
        b.setTypeface(null,Typeface.BOLD);b.setFocusable(true);
    }
    static StateListDrawable rows(Context c){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed},shape(c,0xFF7862D5,0xFFFFE082,3));
        d.addState(new int[]{android.R.attr.state_focused},shape(c,0xFF6352C7,0xFFFFE082,3));
        d.addState(new int[]{},shape(c,0xFF252C44,0xFF56617F,1));return d;
    }
    static void focusable(final View view){
        view.setBackground(background(view.getContext()));view.setFocusable(true);
    }
    static void dialog(android.app.AlertDialog d){
        for(int which:new int[]{-1,-2,-3}){Button b=d.getButton(which);if(b!=null)button(b);}
        if(d.getListView()!=null){d.getListView().setSelector(rows(d.getContext()));d.getListView().setDrawSelectorOnTop(false);}
    }
}
