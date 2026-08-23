package ru.rudrive.voyahadb;

import android.app.*;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.util.ArrayList;

public class MainActivity extends Activity implements AdbClient.Listener {
    private static final String LAN="android.permission.ACCESS_LOCAL_NETWORK";
    private static final String FREE_HOST="192.168.43.1";
    private static final int FREE_PORT=5578;
    private static final String HISTORY_SEP="\u001f";

    private EditText ip,port,command;
    private TextView status,protocolInfo,output,wifiInfo;
    private ScrollView scroll;
    private LinearLayout manualPanel;
    private AdbClient adb;
    private NetworkScanner scanner;
    private SharedPreferences prefs;
    private final ArrayList<String> history=new ArrayList<>();
    private int historyPos=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        adb=new AdbClient(this,this);
        scanner=new NetworkScanner(this);
        prefs=getSharedPreferences("voyah_adb",MODE_PRIVATE);
        loadHistory();
        setContentView(buildUi());
        ensureLan();
    }

    private View buildUi(){
        final LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int side=dp(10),baseTop=dp(10),bottom=dp(8);
        root.setPadding(side,baseTop,side,bottom);
        if(Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener((v,in)->{
                android.graphics.Insets safe=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                int top=Math.max(dp(28),safe.top+dp(6));
                v.setPadding(side+safe.left,top,side+safe.right,bottom+safe.bottom);
                return in;
            });
            root.post(root::requestApplyInsets);
        }else{
            root.setPadding(side,dp(28),side,bottom);
        }

        TextView title=text("VOYAH ADB Console",18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("v1.5 • FREE SA8155 • IHBC / Matrix",12));

        status=text("Отключено",13);root.addView(status);
        protocolInfo=text("Протокол: —",11);root.addView(protocolInfo);
        wifiInfo=text(NetworkUtils.describe(this),11);root.addView(wifiInfo);

        Button free=button("ПОДКЛЮЧИТЬСЯ К VOYAH FREE",v->connectFree());
        root.addView(free,new LinearLayout.LayoutParams(-1,dp(48)));
        root.addView(text("192.168.43.1:5578 • AP Hotspot автомобиля",10));

        LinearLayout connActions=row();
        connActions.addView(button("Отключить",v->adb.disconnect()),weight());
        connActions.addView(button("Ручной режим ▾",v->toggleManual()),weight());
        root.addView(connActions);

        manualPanel=new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.VERTICAL);
        manualPanel.setVisibility(View.GONE);
        LinearLayout conn=row();
        ip=edit("IP",prefs.getString("last_ip",FREE_HOST));
        ip.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        port=edit("Порт",prefs.getString("last_port",String.valueOf(FREE_PORT)));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        conn.addView(ip,new LinearLayout.LayoutParams(0,dp(42),3));
        conn.addView(port,new LinearLayout.LayoutParams(0,dp(42),1));
        manualPanel.addView(conn);
        LinearLayout manual=row();
        manual.addView(button("Подключить",v->connect()),weight());
        manual.addView(button("Тест TCP",v->testTcp()),weight());
        manual.addView(button("Найти :5578",v->discover()),weight());
        manualPanel.addView(manual);
        root.addView(manualPanel);

        TextView sec=text("Быстрые команды",14);
        sec.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(sec);

        LinearLayout q1=row();
        q1.addView(button("Система",v->send("echo '=== SYSTEM ==='; id; echo BOARD=$(getprop ro.product.board); echo VEHICLE=$(getprop ro.vehicle.model); echo ANDROID=$(getprop ro.build.version.release); getenforce")),weight());
        q1.addView(button("CarSignal APK",v->send("pm path com.qinggan.carsignal.service")),weight());
        root.addView(q1);

        LinearLayout q2=row();
        q2.addView(button("VehicleSetting APK",v->send("pm path com.qinggan.app.vehiclesetting")),weight());
        q2.addView(button("CAN service",v->send("pm path com.qinggan.canbus.service")),weight());
        root.addView(q2);

        LinearLayout q3=row();
        q3.addView(button("IHBC props",v->send("getprop | grep -iE 'ihbc|matrix|headlamp|headlight|light'")),weight());
        q3.addView(button("Activity",v->send("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")),weight());
        root.addView(q3);

        LinearLayout q4=row();
        q4.addView(button("IHBC LOG",v->send("logcat | grep -Ei 'IHBC|MATRIXLIGHT|getSupportIHBC|isSupportIHBC|getLightIhbc|setLightIhbc|LightFragment|VehicleCanBusTool'")),weight());
        q4.addView(button("Clear log",v->send("logcat -c")),weight());
        root.addView(q4);

        Button stop=button("■  СТОП / CTRL+C",v->new Thread(()->{
            try{adb.sendCtrlC();}catch(Exception e){onError(e.getMessage());}
        },"adb-ctrl-c").start());
        stop.setTextSize(14);
        root.addView(stop,new LinearLayout.LayoutParams(-1,dp(48)));

        command=edit("Команда shell","getprop ro.product.board");
        command.setImeOptions(EditorInfo.IME_ACTION_SEND);
        command.setOnEditorActionListener((v,action,event)->{
            boolean enter=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN;
            if(action==EditorInfo.IME_ACTION_SEND||enter){send(command.getText().toString());return true;}
            return false;
        });
        root.addView(command,new LinearLayout.LayoutParams(-1,dp(44)));

        LinearLayout cmdActions=row();
        cmdActions.addView(button("◀ История",v->historyPrev()),weight());
        cmdActions.addView(button("Выполнить",v->send(command.getText().toString())),weight());
        cmdActions.addView(button("История ▶",v->historyNext()),weight());
        root.addView(cmdActions);

        LinearLayout ob=row();
        ob.addView(button("Очистить экран",v->output.setText("")),weight());
        ob.addView(button("Копировать",v->copy()),weight());
        root.addView(ob);

        output=text("",11);
        output.setTextIsSelectable(true);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextColor(Color.rgb(20,20,20));
        scroll=new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return root;
    }

    private void ensureLan(){
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(LAN)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{LAN},1201);
    }
    private boolean lanOk(){return Build.VERSION.SDK_INT<37||checkSelfPermission(LAN)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==1201&&(g.length==0||g[0]!=PackageManager.PERMISSION_GRANTED))
            append("\n[СЕТЬ] Разрешите приложению доступ к локальной сети.\n");
    }

    private void toggleManual(){manualPanel.setVisibility(manualPanel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}
    private void refresh(){wifiInfo.setText(NetworkUtils.describe(this));}
    private String host(){return ip.getText().toString().trim();}
    private int p(){return Integer.parseInt(port.getText().toString().trim());}

    private void connectFree(){
        ip.setText(FREE_HOST);port.setText(String.valueOf(FREE_PORT));refresh();
        ensureLan();
        if(!lanOk()){showFreeFailure("Android не разрешил доступ к локальной сети.");return;}

        String local=NetworkUtils.findLocalIpv4();
        status.setText("Подключение к VOYAH FREE…");
        protocolInfo.setText("Протокол: определяем…");
        append("\n[FREE] Подключение к "+FREE_HOST+":"+FREE_PORT+"\n");
        if(local==null||!local.startsWith("192.168.43."))
            append("[FREE] IP телефона: "+local+". Проверьте подключение к AP Hotspot автомобиля.\n");

        new Thread(()->{
            String probe=NetworkUtils.probe(this,FREE_HOST,FREE_PORT,3000);
            if(!probe.startsWith("OK")){
                append("[FREE] "+probe+"\n");
                showFreeFailure("Не удалось связаться с автомобилем по 192.168.43.1:5578.\n\nПроверьте AP Hotspot и Wi‑Fi VOYAH.");
                return;
            }
            try{adb.connect(FREE_HOST,FREE_PORT);}
            catch(Exception e){
                append("[ADB] "+e.getMessage()+"\n");
                showFreeFailure("Автомобиль доступен по Wi‑Fi, но ADB-подключение не установлено.\n\n"+e.getMessage());
            }
        },"voyah-free-connect").start();
    }

    private void showFreeFailure(String message){
        runOnUiThread(()->{
            status.setText("Подключение не удалось");
            protocolInfo.setText("Протокол: —");
            new AlertDialog.Builder(this)
                    .setTitle("Не удалось подключиться к VOYAH FREE")
                    .setMessage(message)
                    .setPositiveButton("OK",null)
                    .show();
        });
    }

    private void testTcp(){
        ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}
        final int x;try{x=p();}catch(Exception e){onError("Неверный порт");return;}
        String h=host();refresh();status.setText("Проверка TCP…");append("\n[TCP] "+h+":"+x+" через Wi‑Fi…\n");
        new Thread(()->{
            String r=NetworkUtils.probe(this,h,x,3000);
            runOnUiThread(()->status.setText(r.startsWith("OK")?"TCP открыт":"TCP недоступен"));
            append("[TCP] "+r+"\n");
        },"tcp-test").start();
    }

    private void discover(){
        ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}
        if(scanner!=null)scanner.cancel();scanner=new NetworkScanner(this);refresh();
        status.setText("Поиск VOYAH :5578…");append("\n[ПОИСК] Сканирую текущую Wi‑Fi подсеть на TCP 5578.\n");
        scanner.scan(FREE_PORT,new NetworkScanner.Listener(){
            public void onProgress(String t){runOnUiThread(()->status.setText(t));}
            public void onFound(String f){runOnUiThread(()->{
                ip.setText(f);port.setText(String.valueOf(FREE_PORT));
                prefs.edit().putString("last_ip",f).putString("last_port",String.valueOf(FREE_PORT)).apply();
                append("[НАЙДЕНО] "+f+":"+FREE_PORT+"\n");connect();
            });}
            public void onFinished(boolean f){if(!f)runOnUiThread(()->{
                status.setText("TCP 5578 не найден");
                append("[ПОИСК] 5578 не найден. Подключите телефон к AP Hotspot VOYAH.\n");
            });}
        });
    }

    private void connect(){
        ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}
        final int x;try{x=p();}catch(Exception e){onError("Неверный порт");return;}
        String h=host();
        prefs.edit().putString("last_ip",h).putString("last_port",String.valueOf(x)).apply();
        refresh();append("\n[ADB] Подключение к "+h+":"+x+"\n");
        new Thread(()->{try{adb.connect(h,x);}catch(Exception e){onError(e.getMessage());}},"adb-connect").start();
    }

    private void send(String c){
        c=c.trim();if(c.isEmpty())return;
        rememberCommand(c);
        final String cmd=c;
        new Thread(()->{try{adb.sendCommand(cmd);}catch(Exception e){onError(e.getMessage());}},"adb-command").start();
    }

    private void loadHistory(){
        String raw=prefs.getString("command_history","");
        if(!raw.isEmpty()){
            String[] parts=raw.split(HISTORY_SEP,-1);
            for(String s:parts)if(!s.trim().isEmpty())history.add(s);
        }
        historyPos=history.size();
    }

    private void rememberCommand(String c){
        if(history.isEmpty()||!history.get(history.size()-1).equals(c))history.add(c);
        while(history.size()>30)history.remove(0);
        historyPos=history.size();
        StringBuilder b=new StringBuilder();
        for(int i=0;i<history.size();i++){if(i>0)b.append(HISTORY_SEP);b.append(history.get(i));}
        prefs.edit().putString("command_history",b.toString()).apply();
    }

    private void historyPrev(){
        if(history.isEmpty())return;
        historyPos=Math.max(0,Math.min(historyPos-1,history.size()-1));
        command.setText(history.get(historyPos));command.setSelection(command.length());
    }
    private void historyNext(){
        if(history.isEmpty())return;
        historyPos=Math.min(history.size(),historyPos+1);
        if(historyPos>=history.size())command.setText("");
        else{command.setText(history.get(historyPos));command.setSelection(command.length());}
    }

    private void copy(){
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Voyah ADB",output.getText()));
        Toast.makeText(this,"Скопировано",Toast.LENGTH_SHORT).show();
    }

    private void append(String s){runOnUiThread(()->{output.append(s);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));});}
    public void onText(String s){append(s);}
    public void onStderr(String s){append("[stderr] "+s);}
    public void onState(String s){runOnUiThread(()->status.setText(s));}
    public void onProtocol(String p){runOnUiThread(()->protocolInfo.setText("Протокол: "+p));}
    public void onExit(int code){append("\n[shell_v2 exit="+code+"]\n");}
    public void onError(String s){runOnUiThread(()->{status.setText("Ошибка");append("\n[ОШИБКА] "+s+"\n");});}

    @Override protected void onDestroy(){if(scanner!=null)scanner.cancel();adb.disconnect();super.onDestroy();}

    private TextView text(String s,int sp){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setPadding(dp(4),dp(2),dp(4),dp(2));return v;
    }
    private EditText edit(String h,String v){
        EditText e=new EditText(this);e.setHint(h);e.setText(v);e.setSingleLine(true);e.setTextSize(14);return e;
    }
    private Button button(String s,View.OnClickListener l){
        Button b=new Button(this);b.setText(s);b.setOnClickListener(l);b.setAllCaps(false);b.setTextSize(11);b.setMinHeight(0);b.setMinimumHeight(0);return b;
    }
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(40),1);}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
