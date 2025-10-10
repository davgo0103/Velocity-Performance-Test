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
    private static final long UPLOAD_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    // 測速 URL（模仿 LagAssist 的配置方式）
    private static final String DOWNLOAD_URL = "http://cachefly.cachefly.net/50mb.test";
    private static final String UPLOAD_URL = "https://c.freetls.fastly.net/%RND%"; // %RND% 會被替換為隨機 UUID

    public NetworkResult testSpeed() throws Exception {
        NetworkResult result = new NetworkResult();

        // 測試延遲
        try {
            result.latency = String.valueOf(pingServer(DOWNLOAD_URL));
        } catch (Exception e) {
            result.latency = "N/A";
        }

        // 測試下載速度（帶重試機制，模仿 LagAssist）
        float downloadSpeed = -1;
        int downloadTries = 0;
        do {
            downloadSpeed = getDownSpeed();
            downloadTries++;
        } while (downloadSpeed == -1 && downloadTries < 3);

        if (downloadSpeed > 0) {
            result.downloadSpeed = String.format("%.2f", downloadSpeed);
            result.testFile = "CacheFly CDN";
        } else {
            result.downloadSpeed = "N/A";
            result.testFile = "測試失敗 (已重試 " + downloadTries + " 次)";
        }

        // 測試上傳速度（帶重試機制）
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
     * 下載速度測試（模仿 LagAssist 的實現）
     * @return 速度 (Mbps)，失敗返回 -1
     */
    private float getDownSpeed() {
        try {
            URL url = new URL(DOWNLOAD_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

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
     * 上傳速度測試（模仿 LagAssist 的實現）
     * @return 速度 (Mbps)，失敗返回 -1
     */
    private float getUpSpeed() {
        try {
            // 替換 %RND% 為隨機 UUID
            String uploadUrl = UPLOAD_URL.replace("%RND%", UUID.randomUUID().toString());

            URL url = new URL(uploadUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            long startTime = System.currentTimeMillis();

            OutputStream output = conn.getOutputStream();

            // 寫入隨機數據
            byte[] buffer = new byte[BUFFER_SIZE];
            new Random().nextBytes(buffer);

            long totalBytes = 0;
            while (totalBytes < UPLOAD_FILE_SIZE) {
                output.write(buffer);
                totalBytes += buffer.length;
            }

            output.flush();
            output.close();

            // 檢查回應
            int responseCode = conn.getResponseCode();
            conn.disconnect();

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
     * Ping 測試（HTTP HEAD 請求）
     */
    private long pingServer(String urlString) throws Exception {
        long startTime = System.currentTimeMillis();

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
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
