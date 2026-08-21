package ru.rudrive.voyahadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.*;
import java.util.*;

public final class NetworkUtils {
    private NetworkUtils() {}

    public static Network findWifiNetwork(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Socket openWifiSocket(Context context, String host, int port, int timeoutMs) throws java.io.IOException {
        Socket socket = new Socket();
        Network wifi = findWifiNetwork(context);
        if (wifi != null) wifi.bindSocket(socket);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        return socket;
    }

    public static boolean isPortOpen(Context context, String host, int port, int timeoutMs) {
        try (Socket s = openWifiSocket(context, host, port, timeoutMs)) { return true; }
        catch (Exception e) { return false; }
    }

    public static String probe(Context context, String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket s = openWifiSocket(context, host, port, timeoutMs)) {
            return "OK — TCP " + host + ":" + port + " открыт, " + (System.currentTimeMillis()-start) + " мс";
        } catch (SocketTimeoutException e) {
            return "TIMEOUT — нет ответа от " + host + ":" + port + ". Проверьте IP, Wi‑Fi и порт adbd.";
        } catch (ConnectException e) {
            return "REFUSED — IP доступен, но порт " + port + " закрыт/не слушается: " + e.getMessage();
        } catch (NoRouteToHostException e) {
            return "NO ROUTE — телефон не видит этот IP через Wi‑Fi: " + e.getMessage();
        } catch (SecurityException e) {
            return "BLOCKED — Android запретил локальную сеть: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR — " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static String findLocalIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            List<String> candidates = new ArrayList<>();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        String host = a.getHostAddress();
                        if (ni.getName().startsWith("wlan")) return host;
                        candidates.add(host);
                    }
                }
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (Exception e) { return null; }
    }

    public static String describe(Context context) {
        String ip = findLocalIpv4();
        Network wifi = findWifiNetwork(context);
        return "Wi‑Fi: " + (ip == null ? "IPv4 не найден" : ip) + " • маршрут: " + (wifi == null ? "системный" : "принудительно Wi‑Fi");
    }
}
