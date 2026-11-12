package com.qianq.kandongman;

import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;

/** Recognizes reviewed whole ad segments in unencrypted, single-variant HLS VOD. */
final class HlsAdFilter {
    static final class Blocked extends IOException {
        Blocked(String message) { super(message); }
    }
    interface Digest {
        String get(String url) throws Exception;
        default void prefetch(List<String> urls) { }
    }
    static final class Incomplete extends IOException {
        Incomplete(Exception cause) { super("已识别到广告候选，但分片校验未完成，请重试", cause); }
    }
    interface Reference { String get(String value) throws Exception; }
    private static final class Sequence {
        final List<String> hashes = new ArrayList<>();
        final List<Long> durations = new ArrayList<>();
    }
    private static final class Segment {
        String info, original, output;
        long duration;
        boolean cut, remove;
    }
    static final class Result {
        final String playlist;
        final long[] cuts;
        Result(String playlist, long[] cuts) { this.playlist = playlist; this.cuts = cuts; }
        long toPlayback(long original) {
            long removed = 0;
            for (int i = 0; i < cuts.length; i += 2) {
                if (original < cuts[i]) break;
                removed += Math.min(original, cuts[i + 1]) - cuts[i];
            }
            return original - removed;
        }
        long toOriginal(long playback) {
            long original = playback;
            for (int i = 0; i < cuts.length; i += 2) {
                if (original < cuts[i]) break;
                original += cuts[i + 1] - cuts[i];
            }
            return original;
        }
    }

    /** Added cuts are measured on the already-filtered cache timeline. */
    static Result combine(long[] previous, Result added) {
        if(previous.length==0)return added;
        if(added==null)return new Result(null,previous);
        final Result prior=new Result(null,previous);
        List<long[]> ranges=new ArrayList<>();
        for(int i=0;i<previous.length;i+=2)ranges.add(new long[]{previous[i],previous[i+1]});
        for(int i=0;i<added.cuts.length;i+=2)ranges.add(new long[]{prior.toOriginal(added.cuts[i]),prior.toOriginal(added.cuts[i+1])});
        Collections.sort(ranges,new Comparator<long[]>(){public int compare(long[] a,long[] b){return Long.compare(a[0],b[0]);}});
        List<Long> values=new ArrayList<>();long start=-1,end=-1;
        for(long[] range:ranges){if(start<0){start=range[0];end=range[1];}else if(range[0]<=end)end=Math.max(end,range[1]);else{values.add(start);values.add(end);start=range[0];end=range[1];}}
        if(start>=0){values.add(start);values.add(end);}long[] cuts=new long[values.size()];for(int i=0;i<cuts.length;i++)cuts[i]=values.get(i);
        return new Result(added.playlist,cuts);
    }

    private final Set<String> blocked = new HashSet<>();
    private final Map<String, String> segments = new HashMap<>();
    private final List<Sequence> sequences = new ArrayList<>();

    static HlsAdFilter read(InputStream input) throws IOException {
        HlsAdFilter result = new HlsAdFilter();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t");
                if (fields[0].equals("block")) {
                    result.blocked.add(key(fields[1]));
                } else if (fields[0].equals("segment")) {
                    result.segments.put(key(fields[1]), fields[2]);
                } else if (fields[0].equals("sequence")) {
                    Sequence sequence = new Sequence();
                    for (String entry : fields[1].split(",")) {
                        String[] parts = entry.split(":");
                        sequence.durations.add(Long.parseLong(parts[0]));
                        sequence.hashes.add(parts[1]);
                    }
                    result.sequences.add(sequence);
                }
            }
        }
        return result;
    }

    private static String key(String address) {
        try {
            URI uri = new URI(address).normalize();
            if (uri.getHost() == null) return "";
            int port = uri.getPort();
            return uri.getHost().toLowerCase(Locale.US) + (port < 0 ? "" : ":" + port) + uri.getRawPath();
        } catch (Exception invalid) { return ""; }
    }

    void check(String address) throws Blocked {
        if (blocked.contains(key(address))) {
            throw new Blocked("这条线路的广告已压进正片，请返回选择其他线路");
        }
    }

    Result online(String address, HlsCache.Fetch fetch, Digest digest) throws Exception {
        for (int depth = 0; depth < 5; depth++) {
            check(address);
            String text = fetch.get(address).trim();
            if (!text.startsWith("#EXTM3U")) return null;
            String variant = null;
            for (String raw : text.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-MEDIA:")) return null;
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    if (variant != null) return null;
                    variant = "";
                } else if (variant != null && variant.isEmpty() && !line.isEmpty() && !line.startsWith("#")) {
                    variant = HlsCache.absolute(address, line);
                }
            }
            if (variant == null) {
                final String base = address;
                Reference absolute = new Reference() {
                    @Override public String get(String value) throws Exception {
                        return HlsCache.absolute(base, value);
                    }
                };
                return filter(text, absolute, absolute, digest);
            }
            if (variant.isEmpty()) return null;
            address = variant;
        }
        return null;
    }

    Result filter(String text, Reference original, Reference output, Digest digest) throws Exception {
        List<Segment> media = new ArrayList<>();
        StringBuilder header = new StringBuilder();
        boolean end = false;
        boolean supported = true;
        Segment pending = new Segment();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equals("#EXT-X-ENDLIST")) end = true;
            else if (line.equals("#EXT-X-DISCONTINUITY")) pending.cut = true;
            else if (line.startsWith("#EXTINF:")) {
                if (pending.info != null) return null;
                pending.info = line;
                pending.duration = Math.round(1000 * Double.parseDouble(line.substring(8).split(",", 2)[0]));
            } else if (line.startsWith("#")) {
                // METHOD=NONE is an explicit unencrypted state, not an unsupported cipher.
                // A playlist containing an actual encrypted section remains unsupported below.
                if (line.equals("#EXT-X-KEY:METHOD=NONE")) {
                    continue;
                } else if (line.equals("#EXTM3U") || line.equals("#EXT-X-INDEPENDENT-SEGMENTS")
                        || line.matches("#EXT-X-(VERSION|TARGETDURATION|MEDIA-SEQUENCE|PLAYLIST-TYPE|DISCONTINUITY-SEQUENCE):.*")) {
                    header.append(line).append('\n');
                } else if (line.startsWith("#EXT")) supported = false;
            } else {
                if (pending.info == null || pending.duration <= 0) return null;
                pending.original = original.get(line);
                pending.output = line;
                pending.remove = segments.containsKey(key(pending.original));
                media.add(pending);
                pending = new Segment();
            }
        }
        if (media.isEmpty()) return null;
        if (!end || !supported) {
            for (Segment segment : media) if (segment.remove) {
                throw new Blocked("检测到已知广告，当前清单格式无法安全跳过，请更换线路");
            }
            return null;
        }

        if (digest != null) {
            Map<String, String> checked = new HashMap<>();
            // Discontinuities can occur inside an ad or in ordinary content. Match a complete
            // reviewed sequence anywhere; never remove a whole run just because it is short.
            for (int a = 0; a < media.size(); a++) {
                for (Sequence sequence : sequences) {
                    int b = a + sequence.durations.size();
                    if (b > media.size()) continue;
                    List<Segment> candidate = media.subList(a, b);
                    if (matches(candidate, sequence, digest, checked)) {
                        for (Segment segment : candidate) segment.remove = true;
                        a = b - 1;
                        break;
                    }
                }
            }
        }
        StringBuilder result = new StringBuilder(header);
        ArrayList<Long> cuts = new ArrayList<>();
        long position = 0;
        long removedFrom = -1;
        boolean cut = false;
        int kept = 0;
        for (Segment segment : media) {
            cut |= segment.cut;
            if (segment.remove) {
                if (removedFrom < 0) removedFrom = position;
                cut = true;
            } else {
                if (removedFrom >= 0) {
                    cuts.add(removedFrom); cuts.add(position);
                    removedFrom = -1;
                }
                if (cut) result.append("#EXT-X-DISCONTINUITY\n");
                cut = false;
                result.append(segment.info).append('\n').append(output.get(segment.output)).append('\n');
                kept++;
            }
            position += segment.duration;
        }
        if (removedFrom >= 0) {
            cuts.add(removedFrom); cuts.add(position);
        }
        if (cuts.isEmpty()) return null;
        if (kept == 0) throw new Blocked("这条线路只匹配到广告分片，请更换线路");
        result.append("#EXT-X-ENDLIST\n");
        long[] ranges = new long[cuts.size()];
        for (int i = 0; i < ranges.length; i++) ranges[i] = cuts.get(i);
        return new Result(result.toString(), ranges);
    }

    private boolean matches(List<Segment> run, Sequence sequence, Digest digest, Map<String, String> checked) throws IOException {
        for (int i = 0; i < run.size(); i++)
            if (Math.abs(run.get(i).duration - sequence.durations.get(i)) > 1) return false;
        for (int i = 0; i < run.size(); i++) {
            String url = run.get(i).original;
            String actual = segments.get(key(url));
            if (actual == null) {
                if (!checked.containsKey(url)) {
                    try { checked.put(url, digest.get(url)); }
                    catch (Exception unavailable) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("检查已取消", unavailable);
                        if (i > 0) throw new Incomplete(unavailable);
                        checked.put(url, "");
                    }
                }
                actual = checked.get(url);
            }
            if (!sequence.hashes.get(i).equals(actual)) return false;
            if (i == 0) {
                List<String> remaining = new ArrayList<>();
                for (int j = 1; j < run.size(); j++) {
                    String next = run.get(j).original;
                    if (!segments.containsKey(key(next)) && !checked.containsKey(next)) remaining.add(next);
                }
                digest.prefetch(remaining);
            }
        }
        return true;
    }

    static String sha256(InputStream in, long deadline) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16384];
        int total = 0;
        for (int n; (n = in.read(buffer)) >= 0;) {
            total += n;
            if (total > 6_000_000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                throw new IOException("广告分片检查超时或超过大小限制");
            }
            digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b & 255) >>> 4, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }
}
