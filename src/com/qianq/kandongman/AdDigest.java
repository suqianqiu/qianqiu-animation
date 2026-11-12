package com.qianq.kandongman;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Per-inspection bounded probes; repeated ad URLs share one complete-file digest. */
final class AdDigest implements HlsAdFilter.Digest, Closeable {
    interface Fetch { String hash(String url, long deadline) throws Exception; }
    private final Fetch fetch;
    private final ExecutorService pool=Executors.newFixedThreadPool(3);
    private final Map<String,Future<String>> pending=new HashMap<>();
    private final long budget;
    private long deadline;
    private boolean closed;
    AdDigest(Fetch fetch){this(fetch,12000);}
    AdDigest(Fetch fetch,long milliseconds){this.fetch=fetch;budget=TimeUnit.MILLISECONDS.toNanos(milliseconds);}
    private Future<String> request(final String url)throws IOException {
        if(closed)throw new IOException("检查已结束");
        Future<String> existing=pending.get(url);if(existing!=null)return existing;
        if(deadline==0)deadline=System.nanoTime()+budget;
        if(System.nanoTime()>=deadline)throw new IOException("广告分片检查超时");
        if(pending.size()>=24)throw new IOException("广告检查达到本次采样上限");
        final long limit=deadline;
        Future<String> task=pool.submit(new Callable<String>(){public String call()throws Exception{return fetch.hash(url,limit);}});
        pending.put(url,task);return task;
    }
    public void prefetch(List<String> urls){for(String url:urls)try{request(url);}catch(IOException exhausted){return;}}
    public String get(String url)throws Exception {
        Future<String> task=request(url);
        try{return task.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("检查已取消",e);}
        catch(TimeoutException e){throw new IOException("广告分片检查超时",e);}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new IOException("分片校验失败",cause);}
    }
    public void close(){closed=true;for(Future<String> task:pending.values())task.cancel(true);pool.shutdownNow();}
}
