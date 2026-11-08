package com.qianq.kandongman;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Checks actual upstream HTML fixtures and ordinary embedded player configurations. */
public class SourcesTest {
    private static String text(Path path) throws Exception {return new String(Files.readAllBytes(path),"UTF-8");}
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]),fixtures=Paths.get(args[1]);
        for(String id:new String[]{"MXdm","akianime","xfdmneo"}) {
            Sources.Source source=new Sources.Source(id,id,new JSONObject(text(root.resolve("assets/"+id+".json"))));
            ArrayList<Sources.Item> found=Sources.parseSearch(source,text(fixtures.resolve(id+"-search.txt")));
            if(found.isEmpty())throw new AssertionError(id+" search empty");
            ArrayList<Sources.Road> roads=Sources.parseChapters(source,text(fixtures.resolve(id+"-chapters.txt")));
            if(roads.isEmpty()||roads.get(0).episodes.isEmpty())throw new AssertionError(id+" chapters empty");
            String media=Sources.extractMedia(text(fixtures.resolve(id+"-play.txt")));
            if(!Sources.media(media))throw new AssertionError(id+" missing embedded media");
            System.out.println(id+": "+found.size()+" search results; "+roads.size()+" roads; media extracted");
        }
        if(Sources.extractMedia("<html>not a player</html>")!=null)throw new AssertionError("HTML must not become media");
        if(Sources.media("https://example.org/movie.m3u8.html")||Sources.http("file:///etc/passwd")||Sources.http("https://name:password@example.org/"))throw new AssertionError("URL checks");
        if(!Sources.media("https://example.org/movie.m3u8?x=a%2Bb"))throw new AssertionError("Signed URL support");
        System.out.println("SourcesTest passed");
    }
}
