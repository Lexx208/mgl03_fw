package ru.rudrive.voyahadb;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbClient {
    public interface Listener {
        void onText(String s);
        void onStderr(String s);
        void onState(String s);
        void onProtocol(String protocol);
        void onExit(int code);
        void onError(String s);
    }

    private static final int CNXN=0x4e584e43, OPEN=0x4e45504f, OKAY=0x59414b4f,
            CLSE=0x45534c43, WRTE=0x45545257, AUTH=0x48545541;
    private static final int VER=0x01000000, DEFMAX=4096;

    // Android shell v2 framing: 1 byte stream id + uint32 little-endian payload length.
    private static final int SH_STDIN=0, SH_STDOUT=1, SH_STDERR=2, SH_EXIT=3,
            SH_CLOSE_STDIN=4, SH_WINDOW_SIZE=5;

    private final Context context;
    private final Listener l;
    private Socket s;
    private InputStream in;
    private OutputStream out;
    private volatile boolean run;
    private volatile boolean shellV2;
    private int local, remote, maxPayload=DEFMAX;
    private String banner="";
    private final AtomicInteger ids=new AtomicInteger(1);
    private final Object lock=new Object();
    private final ByteArrayOutputStream v2Buffer=new ByteArrayOutputStream();

    public AdbClient(Context context, Listener l){
        this.context=context.getApplicationContext();
        this.l=l;
    }

    public boolean isConnected(){return s!=null&&s.isConnected()&&!s.isClosed()&&run;}
    public boolean isShellV2(){return shellV2;}
    public String getBanner(){return banner;}

    public void connect(String host,int port)throws Exception{
        disconnectInternal(false);
        l.onState("TCP → "+host+":"+port);
        try{
            s=NetworkUtils.openWifiSocket(context,host,port,5000);
        }catch(Exception e){
            throw new IOException("TCP не открыт: "+e.getClass().getSimpleName()+": "+e.getMessage(),e);
        }

        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setSoTimeout(7000);
        in=new BufferedInputStream(s.getInputStream());
        out=new BufferedOutputStream(s.getOutputStream());
        l.onState("TCP открыт • ADB handshake…");

        send(CNXN,VER,DEFMAX,"host::\0".getBytes(StandardCharsets.UTF_8));
        Msg h;
        try{h=read();}catch(SocketTimeoutException e){
            throw new IOException("TCP открыт, но adbd не ответил CNXN за 7 с",e);
        }
        if(h.c==AUTH)throw new IOException("TCP открыт, но adbd запросил RSA AUTH (type="+h.a0+")");
        if(h.c!=CNXN)throw new IOException("TCP открыт, но получен не ADB CNXN: 0x"+Integer.toHexString(h.c));

        if(h.a1>0)maxPayload=Math.max(1024,Math.min(h.a1,256*1024));
        banner=new String(h.d,StandardCharsets.UTF_8).replace("\0","").trim();
        l.onText("[ADB] CNXN OK • maxPayload="+maxPayload+(banner.isEmpty()?"":" • "+banner)+"\n");

        boolean advertisedV2=banner.contains("shell_v2");
        boolean opened=false;
        if(advertisedV2){
            opened=openShell("shell,v2,TERM=xterm-256color,pty:",true);
            if(!opened)l.onText("[ADB] shell_v2 заявлен устройством, но открыть его не удалось. Перехожу на legacy shell.\n");
        }
        if(!opened){
            if(!openShell("shell:",false))throw new IOException("ADB подключён, но интерактивный shell не открылся");
        }

        run=true;
        s.setSoTimeout(0);
        l.onProtocol(shellV2?"shell_v2 • PTY":"legacy shell");
        l.onState("Подключено • "+(shellV2?"shell_v2":"legacy")+" • "+host+":"+port);
        new Thread(this::loop,"adb-reader").start();
    }

    private boolean openShell(String service,boolean v2)throws IOException{
        local=ids.getAndIncrement();
        send(OPEN,local,0,(service+"\0").getBytes(StandardCharsets.UTF_8));
        Msg o=read();
        if(o.c==CLSE){local=remote=0;return false;}
        if(o.c!=OKAY||o.a1!=local){local=remote=0;return false;}
        remote=o.a0;
        shellV2=v2;
        v2Buffer.reset();
        return true;
    }

    public void sendCommand(String c)throws IOException{
        if(!isConnected())throw new IOException("ADB не подключён");
        byte[] data=(c+"\n").getBytes(StandardCharsets.UTF_8);
        if(shellV2)writeShellFrame(SH_STDIN,data);else writeAdbStream(data);
    }

    public void sendCtrlC()throws IOException{
        if(!isConnected())throw new IOException("ADB не подключён");
        byte[] data=new byte[]{3};
        if(shellV2)writeShellFrame(SH_STDIN,data);else writeAdbStream(data);
    }

    public void closeStdin()throws IOException{
        if(!isConnected())throw new IOException("ADB не подключён");
        if(shellV2)writeShellFrame(SH_CLOSE_STDIN,new byte[0]);
    }

    private void writeShellFrame(int id,byte[] data)throws IOException{
        byte[] frame=new byte[5+data.length];
        frame[0]=(byte)id;
        putLe32(frame,1,data.length);
        System.arraycopy(data,0,frame,5,data.length);
        writeAdbStream(frame);
    }

    private void writeAdbStream(byte[] d)throws IOException{
        for(int p=0;p<d.length;){
            int n=Math.min(maxPayload,d.length-p);
            byte[] x=Arrays.copyOfRange(d,p,p+n);
            send(WRTE,local,remote,x);
            p+=n;
        }
    }

    private void loop(){
        try{
            while(run){
                Msg m=read();
                if(m.c==WRTE){
                    if(m.d.length>0){
                        if(shellV2)consumeShellV2(m.d);
                        else l.onText(new String(m.d,StandardCharsets.UTF_8));
                    }
                    send(OKAY,local,remote,new byte[0]);
                }else if(m.c==CLSE){
                    l.onState("Shell закрыт автомобилем");
                    break;
                }
            }
        }catch(Exception e){
            if(run)l.onError("ADB shell: "+e.getClass().getSimpleName()+": "+e.getMessage());
        }finally{
            run=false;
            closeTransport();
        }
    }

    private void consumeShellV2(byte[] chunk)throws IOException{
        v2Buffer.write(chunk,0,chunk.length);
        byte[] all=v2Buffer.toByteArray();
        int pos=0;
        while(all.length-pos>=5){
            int id=all[pos]&0xff;
            int len=getLe32(all,pos+1);
            if(len<0||len>1024*1024)throw new IOException("Некорректный shell_v2 frame: "+len);
            if(all.length-pos<5+len)break;
            byte[] data=Arrays.copyOfRange(all,pos+5,pos+5+len);
            dispatchShellFrame(id,data);
            pos+=5+len;
        }
        if(pos>0){
            v2Buffer.reset();
            if(pos<all.length)v2Buffer.write(all,pos,all.length-pos);
        }
    }

    private void dispatchShellFrame(int id,byte[] data){
        if(id==SH_STDOUT){
            if(data.length>0)l.onText(new String(data,StandardCharsets.UTF_8));
        }else if(id==SH_STDERR){
            if(data.length>0)l.onStderr(new String(data,StandardCharsets.UTF_8));
        }else if(id==SH_EXIT){
            int code=data.length>0?(data[0]&0xff):-1;
            l.onExit(code);
        }else if(id==SH_WINDOW_SIZE){
            // Device-side window-size events are informational for this small terminal.
        }else{
            l.onText("[shell_v2] неизвестный frame id="+id+" len="+data.length+"\n");
        }
    }

    public void disconnect(){disconnectInternal(true);}

    private void disconnectInternal(boolean notify){
        boolean was=run||s!=null;
        run=false;
        try{if(out!=null&&local!=0&&remote!=0)send(CLSE,local,remote,new byte[0]);}catch(Exception ignored){}
        closeTransport();
        local=remote=0;
        maxPayload=DEFMAX;
        shellV2=false;
        banner="";
        v2Buffer.reset();
        if(notify&&was)l.onState("Отключено");
    }

    private void closeTransport(){
        try{if(s!=null)s.close();}catch(Exception ignored){}
        s=null;in=null;out=null;
    }

    private void send(int c,int a0,int a1,byte[] d)throws IOException{
        synchronized(lock){
            if(out==null)throw new IOException("Нет TCP соединения");
            le(out,c);le(out,a0);le(out,a1);le(out,d.length);
            int sum=0;for(byte b:d)sum+=b&255;
            le(out,sum);le(out,c^0xffffffff);out.write(d);out.flush();
        }
    }

    private Msg read()throws IOException{
        int c=ri(in),a0=ri(in),a1=ri(in),n=ri(in),sum=ri(in),magic=ri(in);
        if((c^0xffffffff)!=magic)throw new IOException("Некорректный ADB header/magic");
        if(n<0||n>1048576)throw new IOException("Некорректный payload: "+n);
        byte[] d=rf(in,n);int x=0;for(byte b:d)x+=b&255;
        if(x!=sum)throw new IOException("ADB checksum");
        return new Msg(c,a0,a1,d);
    }

    private static byte[] rf(InputStream i,int n)throws IOException{
        byte[] b=new byte[n];int o=0;
        while(o<n){int x=i.read(b,o,n-o);if(x<0)throw new EOFException("Соединение закрыто");o+=x;}
        return b;
    }
    private static int ri(InputStream i)throws IOException{
        int a=i.read(),b=i.read(),c=i.read(),d=i.read();
        if((a|b|c|d)<0)throw new EOFException("Соединение закрыто во время ADB header");
        return a|(b<<8)|(c<<16)|(d<<24);
    }
    private static void le(OutputStream o,int v)throws IOException{
        o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);
    }
    private static void putLe32(byte[] b,int off,int v){
        b[off]=(byte)(v&255);b[off+1]=(byte)((v>>>8)&255);b[off+2]=(byte)((v>>>16)&255);b[off+3]=(byte)((v>>>24)&255);
    }
    private static int getLe32(byte[] b,int off){
        return (b[off]&255)|((b[off+1]&255)<<8)|((b[off+2]&255)<<16)|((b[off+3]&255)<<24);
    }

    private static class Msg{
        int c,a0,a1;byte[]d;
        Msg(int c,int a0,int a1,byte[]d){this.c=c;this.a0=a0;this.a1=a1;this.d=d;}
    }
}
