package com.qianq.kandongman;
import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class AdDigestTest {
    public static void main(String[] args)throws Exception{
        AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();CountDownLatch all=new CountDownLatch(3);
        try(AdDigest d=new AdDigest((url,end)->{calls.incrementAndGet();int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);all.countDown();if(!all.await(2,TimeUnit.SECONDS))throw new IOException("not concurrent");active.decrementAndGet();return url;},3000)){
            d.prefetch(Arrays.asList("a","b","c","a"));if(!d.get("a").equals("a")||!d.get("b").equals("b")||!d.get("c").equals("c"))throw new AssertionError("results");
            if(!d.get("a").equals("a")||calls.get()!=3||peak.get()!=3)throw new AssertionError("dedup or concurrency bound");
        }
        CountDownLatch stopped=new CountDownLatch(1);boolean timeout=false;
        try(AdDigest d=new AdDigest((url,end)->{try{new CountDownLatch(1).await();return "";}finally{stopped.countDown();}},80)){
            try{d.get("slow");}catch(IOException expected){timeout=true;}
        }
        if(!timeout||!stopped.await(2,TimeUnit.SECONDS))throw new AssertionError("deadline or cancellation");
        System.out.println("PASS concurrent full-digest probes, deduplication, deadline and worker cancellation");
    }
}
