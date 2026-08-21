package ru.rudrive.voyahadb;

import android.content.Context;
import java.util.concurrent.*;

public class NetworkScanner {
    public interface Listener { void onProgress(String text); void onFound(String ip); void onFinished(boolean found); }
    private final Context context;
    private volatile boolean cancelled;

    public NetworkScanner(Context context) { this.context = context.getApplicationContext(); }
    public void cancel() { cancelled = true; }

    public void scan(int port, Listener listener) {
        cancelled = false;
        new Thread(() -> {
            String local = NetworkUtils.findLocalIpv4();
            if (local == null || local.lastIndexOf('.') < 0) {
                listener.onProgress("Не найден Wi‑Fi IPv4. Подключите телефон к AP Hotspot VOYAH.");
                listener.onFinished(false); return;
            }
            String prefix = local.substring(0, local.lastIndexOf('.') + 1);
            listener.onProgress("Сканирование " + prefix + "0/24 :" + port + "…");
            ExecutorService pool = Executors.newFixedThreadPool(32);
            CompletionService<String> cs = new ExecutorCompletionService<>(pool);
            int submitted = 0;
            for (int i=1;i<=254;i++) {
                String ip = prefix + i; if (ip.equals(local)) continue; submitted++;
                cs.submit(() -> NetworkUtils.isPortOpen(context, ip, port, 300) ? ip : null);
            }
            boolean found = false;
            try {
                for (int i=0;i<submitted && !cancelled;i++) {
                    String ip = cs.take().get();
                    if (ip != null) { found = true; listener.onFound(ip); break; }
                }
            } catch (Exception ignored) {} finally { pool.shutdownNow(); }
            listener.onFinished(found);
        }, "voyah-net-scan").start();
    }
}
