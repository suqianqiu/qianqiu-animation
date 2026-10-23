package com.qianq.kandongman;
import java.util.*;
/** Labels are claims in source metadata, never guesses based on subtitles. */
final class MediaLabels {
    static boolean mandarin(String name){String n=name.replace("國","国").replace("語","语").replace("話","话").replace("雙","双");return n.matches(".*(国语|普通话|中文配音|汉语配音|国日双语|中日双语|国粤双语|国英双语).*");}
    static String language(String name){if(mandarin(name))return "国语";String n=name.replace("語","语");if(n.contains("粤语"))return "粤语";if(n.contains("日语"))return "日语";if(n.contains("英语"))return "英语";return "语言未标注";}
    static String display(String name){return "【"+language(name)+"】 "+name;}
    static int compare(String a,String b){int rank=Boolean.compare(mandarin(b),mandarin(a));if(rank!=0)return rank;return java.text.Collator.getInstance(Locale.CHINA).compare(a,b);}
}
