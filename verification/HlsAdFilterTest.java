package com.qianq.kandongman;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class HlsAdFilterTest {
    private static int checks;
    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path fixtures = Paths.get(args[0]);
        HlsAdFilter rules = HlsAdFilter.read(new FileInputStream(args[1]));
        for (String row : Files.readAllLines(fixtures.resolve("index.tsv"), StandardCharsets.UTF_8)) {
            String[] fields = row.split("\t");
            String text = new String(Files.readAllBytes(fixtures.resolve(fields[0])), StandardCharsets.UTF_8);
            String address = fields[1];
            boolean blocked = fields[2].equals("block");
            try {
                rules.check(address);
                check(!blocked, "blocked stream " + fields[0]);
            } catch (HlsAdFilter.Blocked e) {
                check(blocked, "unexpected block " + fields[0]);
            }
            HlsAdFilter.Reference absolute = value -> HlsCache.absolute(address, value);
            HlsAdFilter.Result cleaned = rules.filter(text, absolute, absolute, null);
            int oldCount = text.split("#EXTINF:", -1).length - 1;
            check(cleaned != null, "ads found " + fields[0]);
            check(oldCount - (cleaned.playlist.split("#EXTINF:", -1).length - 1)
                    == Integer.parseInt(fields[3]), "only verified segments removed " + fields[0]);
            check(cleaned.toPlayback(cleaned.toOriginal(600000)) == 600000,
                    "timeline roundtrip " + fields[0]);
        }
        String fingerprint = String.join("", Collections.nCopies(64, "a"));
        String catalog = "sequence\t3000:" + fingerprint + ",4000:" + fingerprint + "\n";
        HlsAdFilter portable = HlsAdFilter.read(new ByteArrayInputStream(catalog.getBytes("UTF-8")));
        String manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\nnormal-a.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:3,\nunknown-one.ts\n#EXTINF:4,\nunknown-two.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:5,\nnormal-b.ts\n#EXT-X-ENDLIST\n";
        HlsAdFilter.Reference absolute = value -> "https://new.example/" + value;
        HlsAdFilter.Result filtered = portable.filter(manifest, absolute, absolute, url -> fingerprint);
        check(filtered != null && Arrays.equals(filtered.cuts, new long[]{5000,12000}), "renamed full sequence");
        check(filtered.toPlayback(8000) == 5000 && filtered.toOriginal(5000) == 12000, "resume from inside ad");
        check(filtered.playlist.contains("normal-a.ts") && filtered.playlist.contains("normal-b.ts")
                && !filtered.playlist.contains("unknown-"), "both sides retained");
        check(portable.filter(manifest, absolute, absolute, url -> url.endsWith("one.ts") ? fingerprint : "")
                == null, "partial fingerprint match does not cut");
        check(portable.filter(manifest, absolute, absolute, url -> { throw new IOException("timeout"); })
                == null, "probe failure does not cut");
        check(portable.filter(manifest.replace("#EXT-X-ENDLIST", ""), absolute, absolute, url -> fingerprint)
                == null, "unfinished playlist retained");
        HlsAdFilter.Result none=portable.filter(manifest.replace("#EXTM3U", "#EXTM3U\n#EXT-X-KEY:METHOD=NONE"),absolute,absolute,url->fingerprint);
        check(none!=null&&Arrays.equals(none.cuts,new long[]{5000,12000}),"METHOD NONE is unencrypted, not a bypass");
        check(!none.playlist.contains("#EXT-X-KEY"),"unencrypted state normalized in output");
        check(portable.filter(manifest.replace("#EXTM3U","#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\""),absolute,absolute,url->fingerprint)==null,"actual encrypted media not rewritten");
        check(portable.filter(manifest.replace("#EXTM3U","#EXTM3U\n#EXT-X-KEY:METHOD=NONE,URI=\"bad\""),absolute,absolute,url->fingerprint)==null,"invalid NONE attributes not silently accepted");
        String split=manifest.replace("#EXTINF:4,", "#EXT-X-DISCONTINUITY\n#EXTINF:4,");
        check(Arrays.equals(portable.filter(split,absolute,absolute,url->fingerprint).cuts,new long[]{5000,12000}),"complete ad spans adjacent boundaries");
        String embedded=manifest.replace("#EXT-X-DISCONTINUITY\n", "");
        check(Arrays.equals(portable.filter(embedded,absolute,absolute,url->fingerprint).cuts,new long[]{5000,12000}),"ad window inside a longer content run");
        check(portable.filter(manifest,absolute,absolute,url->"b"+fingerprint.substring(1))==null,"same duration normal media retained");
        final int[] prefetched={0};
        HlsAdFilter.Digest lazy=new HlsAdFilter.Digest(){public String get(String url){return "not-an-ad";}public void prefetch(List<String> urls){prefetched[0]+=urls.size();}};
        check(portable.filter(manifest,absolute,absolute,lazy)==null&&prefetched[0]==0,"normal first fingerprint does not prefetch remaining media");
        boolean incomplete=false;try{portable.filter(manifest,absolute,absolute,url->{if(url.endsWith("one.ts"))return fingerprint;throw new IOException("timeout after confirmed first fragment");});}catch(HlsAdFilter.Incomplete expected){incomplete=true;}
        check(incomplete,"partly confirmed ad cannot silently pass when remaining verification times out");
        String mixed="segment\thttps://new.example/unknown-one.ts\t"+fingerprint+"\n"+catalog;
        HlsAdFilter hybrid=HlsAdFilter.read(new ByteArrayInputStream(mixed.getBytes("UTF-8")));
        check(Arrays.equals(hybrid.filter(manifest,absolute,absolute,url->fingerprint).cuts,new long[]{5000,12000}),"known URL at sequence start still verifies renamed remainder");
        HlsAdFilter.Result combined=HlsAdFilter.combine(new long[]{1000,2000},new HlsAdFilter.Result("local",new long[]{3000,5000}));
        check(Arrays.equals(combined.cuts,new long[]{1000,2000,4000,6000}),"cache rescanning composes old and added timelines");
        check(Arrays.equals(HlsAdFilter.combine(new long[]{1000,2000},new HlsAdFilter.Result("local",new long[]{500,1500})).cuts,new long[]{500,2500}),"new cached cut crossing old cut is merged once");
        check(Arrays.equals(HlsAdFilter.combine(new long[]{1000,2000},null).cuts,new long[]{1000,2000}),"previous cache cuts retained after no new match");
        System.out.println("PASS " + checks + " ad filter checks");
    }
}
