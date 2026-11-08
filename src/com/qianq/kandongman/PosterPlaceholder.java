package com.qianq.kandongman;

import android.widget.TextView;

/** Stable pseudo-random shades keep recycled cards from flashing. */
final class PosterPlaceholder {
    private static final int[] COLORS={0xFFBDC8BA,0xFFD2BDB8,0xFFBAC6CE,0xFFD5C7AE,
        0xFFC6BDD0,0xFFC9BFB2,0xFFCFB6A8,0xFFB0C5BF};
    static int color(String title){int hash=AnimeCatalog.canonical(title).hashCode();hash^=hash>>>16;return COLORS[(hash&0x7fffffff)%COLORS.length];}
    static void style(TextView view,String title){view.setBackgroundColor(color(title));view.setTextColor(0xFF29342F);}
    static String message(String title){return title+"\n\n故事，从这里开始";}
}
