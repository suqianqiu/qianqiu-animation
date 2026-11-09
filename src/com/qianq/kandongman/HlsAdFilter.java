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
    interface Digest { String get(String url) throws Exception; }
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
                if (line.equals("#EXTM3U") || line.equals("#EXT-X-INDEPENDENT-SEGMENTS")
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
            for (int a = 0; a < media.size();) {
                int b = a + 1;
                while (b < media.size() && !media.get(b).cut) b++;
                List<Segment> run = media.subList(a, b);
                if (matches(run, digest, checked)) for (Segment segment : run) segment.remove = true;
                a = b;
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

    private boolean matches(List<Segment> run, Digest digest, Map<String, String> checked) {
        for (Sequence sequence : sequences) {
            if (run.size() != sequence.durations.size()) continue;
            boolean same = true;
            for (int i = 0; i < run.size(); i++) {
                if (Math.abs(run.get(i).duration - sequence.durations.get(i)) > 1) { same = false; break; }
            }
            if (!same) continue;
            for (int i = 0; i < run.size(); i++) {
                String url = run.get(i).original;
                String actual = segments.get(key(url));
                if (actual == null) {
                    if (!checked.containsKey(url)) {
                        try { checked.put(url, digest.get(url)); }
                        catch (Exception unavailable) { checked.put(url, ""); }
                    }
                    actual = checked.get(url);
                }
                if (!sequence.hashes.get(i).equals(actual)) { same = false; break; }
            }
            if (same) return true;
        }
        return false;
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
