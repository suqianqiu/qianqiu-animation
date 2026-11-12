package com.qianq.kandongman;
import java.io.*;import java.nio.file.*;import java.util.*;

/** Offline diagnostic against unmodified 1.6.8 production filter; unavailable probes are explicit. */
public final class InspectBatch {
    public static void main(String[] args)throws Exception{
        Path dir=Paths.get(args[0]),out=dir.resolve("filtered");Files.createDirectories(out);
        Map<String,String> hashes=new HashMap<>();for(String line:Files.readAllLines(dir.resolve("digest-map.tsv"))){String[] p=line.split("\t");hashes.put(p[0],p[1]);}
        HlsAdFilter filter;try(InputStream in=new FileInputStream(args[1])){filter=HlsAdFilter.read(in);}
        StringBuilder report=new StringBuilder();
        for(String row:Files.readAllLines(dir.resolve("index.tsv"))){
            String[] parts=row.split("\t");String id=parts[0],base=parts[1],page=parts[2],text=new String(Files.readAllBytes(dir.resolve(id+".m3u8")),"UTF-8");
            int[] probes={0},missing={0};HlsAdFilter.Reference ref=value->HlsCache.absolute(base,value);
            String blocked="";try{filter.check(page);filter.check(base);}catch(HlsAdFilter.Blocked e){blocked=e.getMessage();}
            HlsAdFilter.Result result=null;String state="",error="";
            try{
                result=filter.filter(text,ref,ref,url->{probes[0]++;String h=hashes.get(url);if(h==null){missing[0]++;throw new IOException("Missing downloaded sample");}return h;});
                state=missing[0]>0?"INCOMPLETE":result==null?"NO_KNOWN_AD_REMOVAL":"FILTERED";
            }catch(Exception e){state="ERROR";error=e.toString();}
            String cleaned=result==null?text:result.playlist;Files.write(out.resolve(id+".m3u8"),cleaned.getBytes("UTF-8"));
            int oldCount=text.split("#EXTINF:",-1).length-1,newCount=cleaned.split("#EXTINF:",-1).length-1;
            report.append(id).append('\t').append(state).append('\t').append(oldCount).append('\t').append(newCount).append('\t').append(result==null?"[]":Arrays.toString(result.cuts)).append('\t').append(probes[0]).append('\t').append(missing[0]).append('\t').append(blocked.replace('\t',' ')).append('\t').append(error.replace('\t',' ').replace('\n',' ')).append('\n');
        }
        Files.write(out.resolve("results.tsv"),report.toString().getBytes("UTF-8"));System.out.print(report);
    }
}
