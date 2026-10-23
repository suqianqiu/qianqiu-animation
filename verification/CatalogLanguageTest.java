package com.qianq.kandongman;
import java.util.*;
public final class CatalogLanguageTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS: "+name);}
 public static void main(String[] args){
  ArrayList<AnimeCatalog.Entry> all=AnimeCatalog.load();Set<String> titles=new HashSet<String>();for(AnimeCatalog.Entry e:all)check(titles.add(e.title),"unique catalog title: "+e.title);
  check(AnimeCatalog.canonical("美食猎人").equals("美食的俘虏"),"Toriko aliases merged");
  check(AnimeCatalog.canonical("百变小樱魔术卡").equals("魔卡少女樱"),"Cardcaptor aliases merged");
  check(!AnimeCatalog.canonical("龙珠").equals(AnimeCatalog.canonical("龙珠超")),"sequels stay distinct");
  check(!AnimeCatalog.canonical("舒克和贝塔").equals(AnimeCatalog.canonical("舒克贝塔")),"remakes stay distinct");
  check(AnimeCatalog.filter(all,"zqdx",'*').get(0).title.equals("正气大侠"),"classic initials searchable");
  check(AnimeCatalog.canonical("猜拳超人").equals("正气大侠"),"classic translated alias searchable");
  check(AnimeCatalog.classic("正气大侠")&&AnimeCatalog.classic("天空战记"),"classic category populated");
  check(MediaLabels.mandarin("柯南 国语版")&&MediaLabels.mandarin("普通話")&&MediaLabels.mandarin("国日双语"),"explicit Mandarin metadata recognized");
  check(!MediaLabels.mandarin("日语中文字幕")&&!MediaLabels.mandarin("中文名：正气大侠"),"subtitles and Chinese titles do not imply Mandarin");
  check(MediaLabels.language("日语中文字幕").equals("日语")&&MediaLabels.language("第1集").equals("语言未标注"),"unknown and Japanese labels correct");
  check(MediaLabels.compare("Z 国语","A 日语")<0,"Mandarin versions sort first");
  System.out.println("CATALOG COUNT: "+all.size());
 }
}
