package com.qianq.kandongman;
import java.util.*;
public final class AnimeCatalogTest {
    static final ArrayList<AnimeCatalog.Entry> all=AnimeCatalog.load();
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void finds(String query,String title){for(AnimeCatalog.Entry e:AnimeCatalog.filter(all,query,'*'))if(e.title.equals(title))return;throw new AssertionError(query+" missing "+title);}
    public static void main(String[] args){
        Set<String> names=new HashSet<String>();
        for(AnimeCatalog.Entry e:all){
            check(names.add(e.title),"Duplicate "+e.title);
            check(e.initials.matches("[a-z0-9]+"),"Invalid initials "+e.title);
            check(AnimeCatalog.filter(all,e.initials,'*').contains(e),"Initials not searchable "+e.title);
            if(e.title.matches("[\\u4e00-\\u9fff]+"))check(e.initials.length()==e.title.length(),"Initial count "+e.title+" "+e.initials);
        }
        finds("mztkn","名侦探柯南"); finds("MZ TKN","名侦探柯南");finds("ＭＺＴＫＮ","名侦探柯南");
        finds("hzw","航海王");finds("海贼王","航海王");finds("One Piece","航海王");
        finds("xmyrz","夏目友人帐");finds("zsdfll","葬送的芙莉莲");
        finds("caswl","长安三万里");finds("xzpq","小猪佩奇");finds("nzzmtnh","哪吒之魔童闹海");
        finds("cxyfh","吹响悠风号");finds("lz","龙珠");finds("lz","龙族");
        check(AnimeCatalog.filter(all,"no-such-title-123456",'*').isEmpty(),"Unknown query");
        for(char c='A';c<='Z';c++)for(AnimeCatalog.Entry e:AnimeCatalog.filter(all,"",c))check(e.letter()==c,"Letter filter");
        check(!AnimeCatalog.filter(all,"",'#').isEmpty(),"Numeric group");
        check(all.size()>=300,"Catalogue too small");
        System.out.println("PASS: "+all.size()+" titles, aliases, initials, case/spacing/fullwidth, ambiguity, A-Z/# filters");
    }
}
