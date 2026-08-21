package ru.rudrive.voyahadb;

import android.app.*;import android.content.SharedPreferences;import android.content.pm.PackageManager;import android.graphics.Color;import android.os.*;import android.text.InputType;import android.view.*;import android.widget.*;

public class MainActivity extends Activity implements AdbClient.Listener {
    private static final String LAN="android.permission.ACCESS_LOCAL_NETWORK";
    private EditText ip,port,command;private TextView status,output,wifiInfo;private ScrollView scroll;private AdbClient adb;private NetworkScanner scanner;private SharedPreferences prefs;

    @Override public void onCreate(Bundle b){super.onCreate(b);adb=new AdbClient(this,this);scanner=new NetworkScanner(this);prefs=getSharedPreferences("voyah_adb",MODE_PRIVATE);setContentView(buildUi());ensureLan();}

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);final int side=dp(10),top=dp(6),bottom=dp(8);root.setPadding(side,top,side,bottom);
        if(Build.VERSION.SDK_INT>=35){root.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets safe=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(side+safe.left,top+safe.top,side+safe.right,bottom+safe.bottom);return in;});root.post(root::requestApplyInsets);}
        TextView title=text("VOYAH ADB Console",18);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(title);root.addView(text("v1.2 • SA8155 / IHBC / Matrix",12));status=text("Отключено",13);root.addView(status);wifiInfo=text(NetworkUtils.describe(this),11);root.addView(wifiInfo);
        LinearLayout conn=row();ip=edit("IP автомобиля",prefs.getString("last_ip","192.168.1.100"));ip.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);port=edit("Порт",prefs.getString("last_port","5555"));port.setInputType(InputType.TYPE_CLASS_NUMBER);conn.addView(ip,new LinearLayout.LayoutParams(0,dp(44),3));conn.addView(port,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(conn);
        LinearLayout r1=row();r1.addView(button("Найти VOYAH",v->discover()),weight());r1.addView(button("Тест TCP",v->testTcp()),weight());root.addView(r1);
        LinearLayout r2=row();r2.addView(button("Подключить",v->connect()),weight());r2.addView(button("Отключить",v->adb.disconnect()),weight());root.addView(r2);
        TextView sec=text("Диагностика IHBC",14);sec.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(sec);
        LinearLayout q1=row();q1.addView(button("Activity",v->send("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")),weight());q1.addView(button("IHBC LOG",v->send("logcat | grep -Ei 'IHBC|MATRIXLIGHT|getSupportIHBC|isSupportIHBC|getLightIhbc|setLightIhbc|LightFragment|VehicleCanBusTool'")),weight());root.addView(q1);
        LinearLayout q2=row();q2.addView(button("Clear log",v->send("logcat -c")),weight());q2.addView(button("Carsignal path",v->send("pm path com.qinggan.carsignal.service")),weight());root.addView(q2);
        root.addView(button("CTRL + C — остановить команду",v->new Thread(()->{try{adb.sendCtrlC();append("\n^C\n");}catch(Exception e){onError(e.getMessage());}}).start()),new LinearLayout.LayoutParams(-1,dp(46)));
        command=edit("Команда shell","getprop ro.product.board");root.addView(command,new LinearLayout.LayoutParams(-1,dp(46)));root.addView(button("Выполнить",v->send(command.getText().toString())),new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout ob=row();ob.addView(button("Очистить экран",v->output.setText("")),weight());ob.addView(button("Копировать",v->copy()),weight());root.addView(ob);
        output=text("",11);output.setTextIsSelectable(true);output.setTypeface(android.graphics.Typeface.MONOSPACE);output.setTextColor(Color.rgb(20,20,20));scroll=new ScrollView(this);scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return root;
    }

    private void ensureLan(){if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(LAN)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{LAN},1201);}
    private boolean lanOk(){return Build.VERSION.SDK_INT<37||checkSelfPermission(LAN)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){super.onRequestPermissionsResult(r,p,g);if(r==1201&&(g.length==0||g[0]!=PackageManager.PERMISSION_GRANTED))append("\n[СЕТЬ] Разрешите приложению доступ к локальной сети.\n");}
    private void refresh(){wifiInfo.setText(NetworkUtils.describe(this));}
    private String host(){return ip.getText().toString().trim();}private int p(){return Integer.parseInt(port.getText().toString().trim());}

    private void testTcp(){ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}final int x;try{x=p();}catch(Exception e){onError("Неверный порт");return;}String h=host();refresh();status.setText("Проверка TCP…");append("\n[TCP] "+h+":"+x+" через Wi‑Fi…\n");new Thread(()->{String r=NetworkUtils.probe(this,h,x,3000);runOnUiThread(()->status.setText(r.startsWith("OK")?"TCP открыт":"TCP недоступен"));append("[TCP] "+r+"\n");}).start();}
    private void discover(){ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}if(scanner!=null)scanner.cancel();scanner=new NetworkScanner(this);refresh();status.setText("Поиск ADB по Wi‑Fi…");append("\n[ПОИСК] Сканирую Wi‑Fi подсеть на TCP 5555.\n");scanner.scan5555(new NetworkScanner.Listener(){public void onProgress(String t){runOnUiThread(()->status.setText(t));}public void onFound(String f){runOnUiThread(()->{ip.setText(f);port.setText("5555");prefs.edit().putString("last_ip",f).putString("last_port","5555").apply();append("[НАЙДЕНО] "+f+":5555\n");connect();});}public void onFinished(boolean f){if(!f)runOnUiThread(()->{status.setText("TCP 5555 не найден");append("[ПОИСК] 5555 не найден. Для известного IP нажмите «Тест TCP».\n");});}});}
    private void connect(){ensureLan();if(!lanOk()){onError("Нет разрешения на локальную сеть");return;}final int x;try{x=p();}catch(Exception e){onError("Неверный порт");return;}String h=host();prefs.edit().putString("last_ip",h).putString("last_port",String.valueOf(x)).apply();refresh();append("\n[ADB] Подключение к "+h+":"+x+"\n");new Thread(()->{try{adb.connect(h,x);}catch(Exception e){onError(e.getMessage());}}).start();}
    private void send(String c){if(c.trim().isEmpty())return;append("\n$ "+c+"\n");new Thread(()->{try{adb.sendCommand(c);}catch(Exception e){onError(e.getMessage());}}).start();}
    private void copy(){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText("Voyah ADB",output.getText()));Toast.makeText(this,"Скопировано",Toast.LENGTH_SHORT).show();}
    private void append(String s){runOnUiThread(()->{output.append(s);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));});}public void onText(String s){append(s);}public void onState(String s){runOnUiThread(()->status.setText(s));}public void onError(String s){runOnUiThread(()->{status.setText("Ошибка");append("\n[ОШИБКА] "+s+"\n");});}
    @Override protected void onDestroy(){if(scanner!=null)scanner.cancel();adb.disconnect();super.onDestroy();}
    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setPadding(dp(4),dp(2),dp(4),dp(2));return v;}private EditText edit(String h,String v){EditText e=new EditText(this);e.setHint(h);e.setText(v);e.setSingleLine(true);e.setTextSize(14);return e;}private Button button(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);b.setAllCaps(false);b.setTextSize(12);b.setMinHeight(0);b.setMinimumHeight(0);return b;}private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(42),1);}private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
