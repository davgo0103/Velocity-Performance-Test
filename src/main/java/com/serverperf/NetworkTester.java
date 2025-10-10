package com.serverperf;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

public class NetworkTester {

    private static final int TIMEOUT_MS = 15000;
    private static final int BUFFER_SIZE = 8192;

    // 測速檔案大小 (bytes)
    private static final long DOWNLOAD_FILE_SIZE = 50 * 1024 * 1024; // 50 MB
    private static final long UPLOAD_FILE_SIZE = 50 * 1024 * 1024; // 50 MB

    // 上傳測試 URL（使用 Tele2 HTTP 上傳）
    private static final String UPLOAD_URL = "http://speedtest.tele2.net/upload.php";

    // 測速伺服器列表
    private static final TestServer[] TEST_SERVERS = {
        new TestServer("Cloudflare DNS", "https://1.1.1.1"), // 使用 1.1.1.1 測試
        new TestServer("CacheFly CDN", "http://cachefly.cachefly.net/50mb.test"),
        new TestServer("Tele2 Speedtest", "http://speedtest.tele2.net/100MB.zip"),
        new TestServer("Cloudflare Speed", "https://speed.cloudflare.com/__down?bytes=50000000")
    };

    private static class TestServer {
        String name;
        String downloadUrl;

        TestServer(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }

    public NetworkResult testSpeed() throws Exception {
        NetworkResult result = new NetworkResult();

        // 尋找 ping 最低的伺服器
        TestServer bestServer = findBestServer();
        if (bestServer == null) {
            throw new Exception("無法連接到任何測速伺服器");
        }

        result.testFile = bestServer.name;

        // 測試延遲
        try {
            result.latency = String.valueOf(pingServer(bestServer.downloadUrl));
        } catch (Exception e) {
            result.latency = "N/A";
        }

        // 測試下載速度（帶重試機制，模仿 LagAssist）
        float downloadSpeed = -1;
        int downloadTries = 0;
        do {
            downloadSpeed = getDownSpeed(bestServer.downloadUrl);
            downloadTries++;
        } while (downloadSpeed == -1 && downloadTries < 3);

        if (downloadSpeed > 0) {
            result.downloadSpeed = String.format("%.2f", downloadSpeed);
        } else {
            result.downloadSpeed = "N/A";
            result.testFile += " (下載失敗)";
        }

        // 上傳測試（使用 FTP 到 Tele2）
        float uploadSpeed = -1;
        int uploadTries = 0;
        do {
            uploadSpeed = getUpSpeed();
            uploadTries++;
        } while (uploadSpeed == -1 && uploadTries < 3);

        if (uploadSpeed > 0) {
            result.uploadSpeed = String.format("%.2f", uploadSpeed);
        } else {
            result.uploadSpeed = "N/A (重試 " + uploadTries + " 次後失敗)";
        }

        result.jitter = "N/A";

        return result;
    }

    /**
     * 尋找 ping 最低的伺服器
     */
    private TestServer findBestServer() {
        TestServer bestServer = null;
        long minLatency = Long.MAX_VALUE;

        System.out.println("正在測試伺服器延遲...");

        for (TestServer server : TEST_SERVERS) {
            try {
                long latency = pingServer(server.downloadUrl);
                System.out.println("  " + server.name + ": " + latency + " ms");

                if (latency > 0 && latency < minLatency) {
                    minLatency = latency;
                    bestServer = server;
                }
            } catch (Exception e) {
                System.err.println("  " + server.name + ": 連接失敗 - " + e.getMessage());
            }
        }

        if (bestServer != null) {
            System.out.println("選擇最佳伺服器: " + bestServer.name + " (" + minLatency + " ms)");
        }

        return bestServer;
    }

    /**
     * 下載速度測試（模仿 LagAssist 的實現）
     * @return 速度 (Mbps)，失敗返回 -1
     */
    private float getDownSpeed(String downloadUrl) {
        try {
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            long startTime = System.currentTimeMillis();

            InputStream input = conn.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytes = 0;

            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                totalBytes += bytesRead;
            }

            input.close();
            conn.disconnect();

            float timeTaken = (System.currentTimeMillis() - startTime) / 1000.0f;

            // 計算速度：(bytes * 8) / time / 1,000,000 = Mbps
            float mbps = (totalBytes * 8.0f) / timeTaken / 1_000_000.0f;

            return mbps;

        } catch (Exception e) {
            System.err.println("下載測試失敗: " + e.getMessage());
            return -1;
        }
    }

    /**
     * 上傳速度測試（使用 Tele2 HTTP PUT，模仿 curl -T）
     * @return 速度 (Mbps)，失敗返回 -1
     */
    private float getUpSpeed() {
        try {
            URL url = new URL(UPLOAD_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT"); // 使用 PUT 方法（模仿 curl -T）
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS * 4); // 上傳 50MB 需要更長時間
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(UPLOAD_FILE_SIZE); // 設定上傳大小

            long startTime = System.currentTimeMillis();

            OutputStream output = conn.getOutputStream();

            // 寫入隨機數據
            byte[] buffer = new byte[BUFFER_SIZE];
            new Random().nextBytes(buffer);

            long bytesWritten = 0;
            while (bytesWritten < UPLOAD_FILE_SIZE) {
                output.write(buffer);
                bytesWritten += buffer.length;
            }

            output.flush();
            output.close();

            // 檢查回應
            int responseCode = conn.getResponseCode();
            conn.disconnect();

            if (responseCode < 200 || responseCode >= 400) {
                System.err.println("上傳測試失敗: HTTP " + responseCode);
                return -1;
            }

            float timeTaken = (System.currentTimeMillis() - startTime) / 1000.0f;

            // 計算速度：(bytes * 8) / time / 1,000,000 = Mbps
            float mbps = (UPLOAD_FILE_SIZE * 8.0f) / timeTaken / 1_000_000.0f;

            return mbps;

        } catch (Exception e) {
            System.err.println("上傳測試失敗: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Ping 測試（HTTP/HTTPS HEAD 請求）
     */
    private long pingServer(String urlString) throws Exception {
        long startTime = System.currentTimeMillis();

        URL url = new URL(urlString);
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 400) {
                return System.currentTimeMillis() - startTime;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        throw new Exception("伺服器無回應");
    }

}

class NetworkResult {
    String downloadSpeed = "N/A";
    String uploadSpeed = "N/A";
    String latency = "N/A";
    String jitter = "N/A";
    String testFile = "N/A";
    long totalBytes = 0;
    long totalTime = 0;
}
