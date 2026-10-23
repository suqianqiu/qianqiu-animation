package com.qianq.kandongman;
public final class MediaGuardTest {
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);System.out.println("PASS: "+name);}
 public static void main(String[] args)throws Exception{
  check(MediaGuard.blocked("https://ads.example.org/movie.mp4"),"ad subdomain blocked");
  check(MediaGuard.blocked("https://x.doubleclick.net/track"),"known ad host blocked");
  check(MediaGuard.blocked("https://cdn.example.org/preroll/a.m3u8"),"preroll path blocked");
  check(MediaGuard.blocked("https://cdn.example.org/movie.mp4?is_ad=1"),"explicit ad query blocked");
  check(MediaGuard.blocked("intent://open-app")&&MediaGuard.blocked("javascript:alert(1)"),"external app and script schemes blocked");
  check(!MediaGuard.blocked("https://cdn.example.org/ab12ad34/video.m3u8?token=ad985a"),"signed CDN hashes remain valid");
  check(MediaGuard.sameDocument("https://example.org/play/1?a=2","https://example.org/play/1?a=2#video"),"same document fragment allowed");
  check(!MediaGuard.sameDocument("https://example.org/play/1","https://evil.example.org/play/1")&&!MediaGuard.sameDocument("https://example.org/play/1","https://example.org/promo"),"cross-origin and top-level page substitution blocked");
  MediaGuard.checkPlaylist("https://cdn.example.org/index.m3u8","#EXTM3U\n#EXTINF:2,\nab12ad34.ts\n#EXT-X-ENDLIST");
  boolean rejected=false;try{MediaGuard.checkPlaylist("https://cdn.example.org/index.m3u8","#EXTM3U\n#EXTINF:2,\nads/pre.ts\n#EXT-X-ENDLIST");}catch(Exception expected){rejected=true;}check(rejected,"explicit ad resources in HLS rejected");
 }
}
