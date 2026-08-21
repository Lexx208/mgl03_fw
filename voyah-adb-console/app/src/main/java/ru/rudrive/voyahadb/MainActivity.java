package ru.rudrive.voyahadb;

import android.app.*;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity implements AdbClient.Listener {
    private static final String LAN="android.permission.ACCESS_LOCAL_NETWORK";
    private static final String FREE_HOST="192.168.43.1";
    private static final int FREE_PORT=5578;

    private EditText ip,port,command;
    private TextView status,output,wifiInfo;
    private ScrollView scroll;
    private AdbClient adb;
    private NetworkScanner scanner;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        adb=new AdbClient(this,this);
        scanner=new NetworkScanner(this);
        prefs=getSharedPreferences("voyah_adb",MODE_PRIVATE);
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

        TextView title=text("VOYAH ADB Console",18);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(title);
        root.addView(text("v1.4 • VOYAH FREE SA8155 • IHBC / Matrix",12));
        status=text("Отключено",13);root.addView(status);
        wifiInfo=text(NetworkUtils.describe(this),11);root.addView(wifiInfo);

        TextView quick=text("Подключение к VOYAH FREE",14);quick.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(quick);
        TextView hint=text("Включите AP Hotspot в автомобиле и подключите телефон к Wi‑Fi автомобиля.",11);root.addView(hint);
        Button free=button("ПОДКЛЮЧИТЬСЯ К FREE  •  192.168.43.1:5578",v->connectFree());
        root.addView(free,new LinearLayout.LayoutParams(-1,dp(48)));
        root.addView(button("Отключить",v->adb.disconnect()),new LinearLayout.LayoutParams(-1,dp(40)));

        TextView adv=text("Ручной режим",13);adv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(adv);
        LinearLayout conn=row();
        ip=edit("IP",prefs.getString("last_ip",FREE_HOST));ip.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        port=edit("Порт",prefs.getString("last_port",String.valueOf(FREE_PORT)));port.setInputType(InputType.TYPE_CLASS_NUMBER);
        conn.addView(ip,new LinearLayout.LayoutParams(0,dp(42),3));conn.addView(port,new LinearLayout.LayoutParams(0,dp(42),1));root.addView(conn);
        LinearLayout manual=row();
        manual.addView(button("Подключить",v->connect()),weight());
        manual.addView(button("Тест TCP",v->testTcp()),weight());
        manual.addView(button("Найти :5578",v->discover()),weight());
        root.addView(manual);

        TextView sec=text("Диагностика IHBC",14);sec.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(sec);
        LinearLayout q1=row();
        q1.addView(button("Activity",v->send("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")),weight());
        q1.addView(button("IHBC LOG",v->send("logcat | grep -Ei 'IHBC|MATRIXLIGHT|getSupportIHBC|isSupportIHBC|getLightIhbc|setLightIhbc|LightFragment|VehicleCanBusTool'")),weight());
        root.addView(q1);
        LinearLayout q2=row();
        q2.addView(button("Clear log",v->send("logcat -c")),weight());
        q2.addView(button("Carsignal path",v->send("pm path com.qinggan.carsignal.service")),weight());
        root.addView(q2);

        root.addView(button("CTRL + C — остановить текущую команду",v->new Thread(()->{
            try{adb.sendCtrlC();append("\n^C\n");}catch(Exception e){onError(e.getMessage());}
        }).start()),new LinearLayout.LayoutParams(-1,dp(46)));

        command=edit("Команда shell","getprop ro.product.board");root.addView(command,new LinearLayout.LayoutParams(-1,dp(44)));
        root.addView(button("Выполнить",v->send(command.getText().toString())),new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout ob=row();
        ob.addView(button("Очистить экран",v->output.setText("")),weight());
        ob.addView(button("Копировать",v->copy()),weight());
        root.addView(ob);

        output=text("",11);output.setTextIsSelectable(true);output.setTypeface(android.graphics.Typeface.MONOSPACE);output.setTextColor(Color.rgb(20,20,20));
        scroll=new ScrollView(this);scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return root;
    }

    private void ensureLan(){if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(LAN)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{LAN},1201);}
    private boolean lanOk(){return Build.VERSION.SDK_INT<37||checkSelfPermission(LAN)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){super.onRequestPermissionsResult(r,p,g);if(r==1201&&(g.length==0||g[0]!=PackageManager.PERMISSION_GRANTED))append("\n[СЕТЬ] Разрешите приложению доступ к локальной сети.\n");}

    private void refresh(){wifiInfo.setText(NetworkUtils.describe(this));}
    private String host(){return ip.getText().toString().trim();}
    private int p(){return Integer.parseInt(port.getText().toString().trim());}

    private void connectFree(){
        ip.setText(FREE_HOST);port.setText(String.valueOf(FREE_PORT));refresh();
        ensureLan();
        if(!lanOk()){showFreeFailure("Android не разрешил доступ к локальной сети.");return;}

        String local=NetworkUtils.findLocalIpv4();
        status.setText("Подключение к VOYAH FREE…");
        append("\n[FREE] Подключение к "+FREE_HOST+":"+FREE_PORT+"\n");
        if(local==null||!local.startsWith("192.168.43.")){
            append("[FREE] IP телефона: "+local+". Телефон может быть подключён не к AP Hotspot автомобиля.\n");
        }

        new Thread(()->{
            String probe=NetworkUtils.probe(this,FREE_HOST,FREE_PORT,3000);
            if(!probe.startsWith("OK")){
                append("[FREE] "+probe+"\n");
                showFreeFailure("Не удалось связаться с автомобилем по 192.168.43.1:5578.\n\nПроверьте, что AP Hotspot включён и телефон подключён к Wi‑Fi VOYAH.");
                return;
            }
            try{
                adb.connect(FREE_HOST,FREE_PORT);
            }catch(Exception e){
                append("[ADB] "+e.getMessage()+"\n");
                showFreeFailure("Автомобиль доступен по Wi‑Fi, но ADB-подключение не установлено.\n\n"+e.getMessage());
            }
        },"voyah-free-connect").start();
    }

    private void showFreeFailure(String message){
        runOnUiThread(()->{
            status.setText("Подключение не удалось");
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
        new Thread(()->{String r=NetworkUtils.probe(this,h,x,3000);runOnUiThread(()->status.setText(r.startsWith("OK")?"TCP открыт":"TCP недоступен"));append("[TCP] "+r+"\n");}).start();
    }

    private void discover(){
        ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}
        if(scanner!=null)scanner.cancel();scanner=new NetworkScanner(this);refresh();status.setText("Поиск VOYAH :5578…");append("\n[ПОИСК] Сканирую текущую Wi‑Fi подсеть на TCP 5578.\n");
        scanner.scan(FREE_PORT,new NetworkScanner.Listener(){
            public void onProgress(String t){runOnUiThread(()->status.setText(t));}
            public void onFound(String f){runOnUiThread(()->{ip.setText(f);port.setText(String.valueOf(FREE_PORT));prefs.edit().putString("last_ip",f).putString("last_port",String.valueOf(FREE_PORT)).apply();append("[НАЙДЕНО] "+f+":"+FREE_PORT+"\n");connect();});}
            public void onFinished(boolean f){if(!f)runOnUiThread(()->{status.setText("TCP 5578 не найден");append("[ПОИСК] 5578 не найден. Подключите телефон к AP Hotspot VOYAH и попробуйте 192.168.43.1:5578.\n");});}
        });
    }

    private void connect(){
        ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}
        final int x;try{x=p();}catch(Exception e){onError("Неверный порт");return;}
        String h=host();prefs.edit().putString("last_ip",h).putString("last_port",String.valueOf(x)).apply();refresh();append("\n[ADB] Подключение к "+h+":"+x+"\n");
        new Thread(()->{try{adb.connect(h,x);}catch(Exception e){onError(e.getMessage());}}).start();
    }

    private void send(String c){if(c.trim().isEmpty())return;append("\n$ "+c+"\n");new Thread(()->{try{adb.sendCommand(c);}catch(Exception e){onError(e.getMessage());}}).start();}
    private void copy(){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText("Voyah ADB",output.getText()));Toast.makeText(this,"Скопировано",Toast.LENGTH_SHORT).show();}
    private void append(String s){runOnUiThread(()->{output.append(s);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));});}
    public void onText(String s){append(s);}
    public void onState(String s){runOnUiThread(()->status.setText(s));}
    public void onError(String s){runOnUiThread(()->{status.setText("Ошибка");append("\n[ОШИБКА] "+s+"\n");});}
    @Override protected void onDestroy(){if(scanner!=null)scanner.cancel();adb.disconnect();super.onDestroy();}

    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setPadding(dp(4),dp(2),dp(4),dp(2));return v;}
    private EditText edit(String h,String v){EditText e=new EditText(this);e.setHint(h);e.setText(v);e.setSingleLine(true);e.setTextSize(14);return e;}
    private Button button(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);b.setAllCaps(false);b.setTextSize(11);b.setMinHeight(0);b.setMinimumHeight(0);return b;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(40),1);}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
