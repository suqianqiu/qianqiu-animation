package com.qianq.kandongman;
import java.io.*;
import java.security.cert.*;
import javax.net.ssl.*;

public class TlsTest {
    public static void main(String[] args) throws Exception {
        X509TrustManager trust;
        try(InputStream in=new FileInputStream(args[0])){trust=CompatTls.trustManager(in);}
        if(trust.getAcceptedIssuers().length<6)throw new AssertionError("Trusted roots missing");
        X509Certificate untrusted;
        try(InputStream in=new FileInputStream(args[1])){untrusted=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}
        try{trust.checkServerTrusted(new X509Certificate[]{untrusted},"RSA");throw new AssertionError("Untrusted self-signed server was accepted");}
        catch(CertificateException expected){System.out.println("TlsTest passed: bundled public roots loaded; untrusted self-signed server rejected");}
    }
}
