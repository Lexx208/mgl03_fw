package ru.rudrive.voyahadb;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbClient {
    public interface Listener { void onText(String s); void onState(String s); void onError(String s); }
    private static final int CNXN=0x4e584e43, OPEN=0x4e45504f, OKAY=0x59414b4f, CLSE=0x45534c43, WRTE=0x45545257, AUTH=0x48545541;
    private static final int VER=0x01000000, DEFMAX=4096;
    private final Context context; private final Listener l; private Socket s; private InputStream in; private OutputStream out; private volatile boolean run;
    private int local, remote, maxPayload=DEFMAX; private final AtomicInteger ids=new AtomicInteger(1); private final Object lock=new Object();

    public AdbClient(Context context, Listener l){this.context=context.getApplicationContext();this.l=l;}
    public boolean isConnected(){return s!=null&&s.isConnected()&&!s.isClosed()&&run;}

    public void connect(String host,int port)throws Exception{
        disconnect(); l.onState("TCP → "+host+":"+port);
        try{s=NetworkUtils.openWifiSocket(context,host,port,5000);}catch(Exception e){throw new IOException("TCP не открыт: "+e.getClass().getSimpleName()+": "+e.getMessage(),e);}
        s.setTcpNoDelay(true);s.setKeepAlive(true);s.setSoTimeout(7000);in=new BufferedInputStream(s.getInputStream());out=new BufferedOutputStream(s.getOutputStream());l.onState("TCP открыт • ADB handshake…");
        send(CNXN,VER,DEFMAX,"host::\0".getBytes(StandardCharsets.UTF_8));
        Msg h;try{h=read();}catch(SocketTimeoutException e){throw new IOException("TCP открыт, но adbd не ответил CNXN за 7 с",e);}
        if(h.c==AUTH)throw new IOException("TCP открыт, но adbd запросил RSA AUTH (type="+h.a0+")");
        if(h.c!=CNXN)throw new IOException("TCP открыт, но получен не ADB CNXN: 0x"+Integer.toHexString(h.c));
        if(h.a1>0)maxPayload=Math.max(1024,Math.min(h.a1,256*1024));
        String banner=new String(h.d,StandardCharsets.UTF_8).replace("\0","").trim();l.onText("[ADB] CNXN OK • maxPayload="+maxPayload+(banner.isEmpty()?"":" • "+banner)+"\n");
        local=ids.getAndIncrement();send(OPEN,local,0,"shell:\0".getBytes(StandardCharsets.UTF_8));Msg o=read();
        if(o.c==CLSE)throw new IOException("adbd закрыл shell сразу после OPEN");
        if(o.c!=OKAY||o.a1!=local)throw new IOException("ADB подключён, но shell не открылся: cmd=0x"+Integer.toHexString(o.c));
        remote=o.a0;run=true;s.setSoTimeout(0);l.onState("Подключено: "+host+":"+port);new Thread(this::loop,"adb-reader").start();
    }

    public void sendCommand(String c)throws IOException{if(!isConnected())throw new IOException("ADB не подключён");write((c+"\n").getBytes(StandardCharsets.UTF_8));}
    public void sendCtrlC()throws IOException{if(!isConnected())throw new IOException("ADB не подключён");write(new byte[]{3});}
    private void write(byte[] d)throws IOException{for(int p=0;p<d.length;){int n=Math.min(maxPayload,d.length-p);byte[] x=new byte[n];System.arraycopy(d,p,x,0,n);send(WRTE,local,remote,x);p+=n;}}
    private void loop(){try{while(run){Msg m=read();if(m.c==WRTE){if(m.d.length>0)l.onText(new String(m.d,StandardCharsets.UTF_8));send(OKAY,local,remote,new byte[0]);}else if(m.c==CLSE){l.onState("Shell закрыт автомобилем");break;}}}catch(Exception e){if(run)l.onError("ADB shell: "+e.getClass().getSimpleName()+": "+e.getMessage());}finally{run=false;close();}}
    public void disconnect(){boolean was=run;run=false;try{if(out!=null&&local!=0&&remote!=0)send(CLSE,local,remote,new byte[0]);}catch(Exception ignored){}close();local=remote=0;maxPayload=DEFMAX;if(was||l!=null)l.onState("Отключено");}
    private void close(){try{if(s!=null)s.close();}catch(Exception ignored){}s=null;in=null;out=null;}
    private void send(int c,int a0,int a1,byte[] d)throws IOException{synchronized(lock){if(out==null)throw new IOException("Нет TCP соединения");le(out,c);le(out,a0);le(out,a1);le(out,d.length);int sum=0;for(byte b:d)sum+=b&255;le(out,sum);le(out,c^0xffffffff);out.write(d);out.flush();}}
    private Msg read()throws IOException{int c=ri(in),a0=ri(in),a1=ri(in),n=ri(in),sum=ri(in),magic=ri(in);if((c^0xffffffff)!=magic)throw new IOException("Некорректный ADB header/magic");if(n<0||n>1048576)throw new IOException("Некорректный payload: "+n);byte[] d=rf(in,n);int x=0;for(byte b:d)x+=b&255;if(x!=sum)throw new IOException("ADB checksum");return new Msg(c,a0,a1,d);}
    private static byte[] rf(InputStream i,int n)throws IOException{byte[] b=new byte[n];int o=0;while(o<n){int x=i.read(b,o,n-o);if(x<0)throw new EOFException("Соединение закрыто");o+=x;}return b;}
    private static int ri(InputStream i)throws IOException{int a=i.read(),b=i.read(),c=i.read(),d=i.read();if((a|b|c|d)<0)throw new EOFException("Соединение закрыто во время ADB header");return a|(b<<8)|(c<<16)|(d<<24);}
    private static void le(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
    private static class Msg{int c,a0,a1;byte[]d;Msg(int c,int a0,int a1,byte[]d){this.c=c;this.a0=a0;this.a1=a1;this.d=d;}}
}
