package com.serverperf;

import fr.bmartel.speedtest.SpeedTestReport;
import fr.bmartel.speedtest.SpeedTestSocket;
import fr.bmartel.speedtest.inter.ISpeedTestListener;
import fr.bmartel.speedtest.model.SpeedTestError;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class NetworkTester {

    // 使用多個測試伺服器，測試時會選擇最快的（優先台灣和亞洲）
    private static final String[] TEST_SERVERS = {
        "http://speedtest.hinet.net/test_100mb.zip",            // 台灣 (中華電信)
        "http://ipv4.download.thinkbroadband.com/10MB.zip",     // 英國
        "http://proof.ovh.net/files/10Mb.dat",                  // OVH (全球)
        "http://speedtest.tele2.net/10MB.zip",                  // 瑞典
        "http://bouygues.testdebit.info/10M.iso",               // 法國
        "http://ipv4.ikoula.testdebit.info/10M.iso"             // 法國
    };

    private static final int TIMEOUT_SECONDS = 30;

    public NetworkResult testSpeed() throws Exception {
        NetworkResult result = new NetworkResult();

        // 先找到最快的伺服器
        String bestServer = findBestServer();
        if (bestServer == null) {
            throw new Exception("無法連接到任何測試伺服器");
        }

        result.testFile = getServerDisplayName(bestServer);

        // 先測試延遲（在下載測試之前）
        try {
            result.latency = String.format("%.0f", measureLatency(bestServer));
        } catch (Exception e) {
            result.latency = "N/A";
        }

        // 測試下載速度
        result.downloadSpeed = testDownload(bestServer);

        // 測試上傳速度（使用最近的上傳伺服器）
        try {
            String uploadServer = findBestUploadServer();
            if (uploadServer != null) {
                result.uploadSpeed = testUpload(uploadServer);
            } else {
                result.uploadSpeed = "N/A";
            }
        } catch (Exception e) {
            // 如果上傳測試失敗，標記為 N/A
            result.uploadSpeed = "N/A";
        }

        return result;
    }

    /**
     * 找到最近的上傳伺服器（根據延遲）
     */
    private String findBestUploadServer() {
        // HTTP 上傳伺服器列表
        String[] httpUploadServers = {
            "http://ipv4.ikoula.testdebit.info/",      // 法國
            "http://bouygues.testdebit.info/",         // 法國
            "http://proof.ovh.net/",                   // 全球 OVH
            "http://speedtest.tele2.net/"              // 瑞典
        };

        String bestServer = null;
        double minLatency = Double.MAX_VALUE;

        // 測試每個伺服器的延遲，選擇最快的
        for (String server : httpUploadServers) {
            try {
                double latency = measureUploadLatency(server);
                if (latency < minLatency && latency > 0 && latency < 5000) {
                    minLatency = latency;
                    bestServer = server;
                }
            } catch (Exception e) {
                // 該伺服器無法連接，嘗試下一個
            }
        }

        // 如果找到可用的 HTTP 伺服器，返回
        if (bestServer != null) {
            return bestServer;
        }

        // 如果 HTTP 都失敗，嘗試 FTP
        try {
            String fileName = "test_" + System.currentTimeMillis() + ".tmp";
            String ftpServer = "ftp://speedtest.tele2.net/upload/" + fileName;
            // FTP 通常都可用
            return ftpServer;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 測量上傳伺服器的延遲
     */
    private double measureUploadLatency(String serverUrl) throws Exception {
        java.net.URL url = new java.net.URL(serverUrl);
        long startTime = System.currentTimeMillis();

        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setRequestMethod("HEAD");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        int responseCode = connection.getResponseCode();
        long endTime = System.currentTimeMillis();

        connection.disconnect();

        if (responseCode >= 200 && responseCode < 500) {
            return (double) (endTime - startTime);
        } else {
            throw new Exception("無法連接");
        }
    }

    /**
     * 測試下載速度
     */
    private String testDownload(String serverUrl) throws Exception {
        SpeedTestSocket speedTestSocket = new SpeedTestSocket();
        speedTestSocket.setSocketTimeout(TIMEOUT_SECONDS * 1000);
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[]{"N/A"};
        final Exception[] asyncException = new Exception[1];

        speedTestSocket.addSpeedTestListener(new ISpeedTestListener() {
            @Override
            public void onCompletion(SpeedTestReport report) {
                try {
                    BigDecimal transferRateBit = report.getTransferRateBit();
                    double mbps = transferRateBit.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP).doubleValue();
                    result[0] = String.format("%.2f", mbps);
                } catch (Exception e) {
                    asyncException[0] = e;
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onError(SpeedTestError speedTestError, String errorMessage) {
                asyncException[0] = new Exception("下載測試錯誤: " + errorMessage);
                latch.countDown();
            }

            @Override
            public void onProgress(float percent, SpeedTestReport report) {
                // 進度監控
            }
        });

        speedTestSocket.startDownload(serverUrl);

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            speedTestSocket.forceStopTask();
            throw new Exception("下載測試超時");
        }

        if (asyncException[0] != null) {
            throw asyncException[0];
        }

        return result[0];
    }

    /**
     * 測試上傳速度
     */
    private String testUpload(String serverUrl) throws Exception {
        SpeedTestSocket speedTestSocket = new SpeedTestSocket();
        speedTestSocket.setSocketTimeout(TIMEOUT_SECONDS * 1000);

        // 設定上傳塊大小（提升上傳效能）
        speedTestSocket.setUploadChunkSize(65536);

        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[]{"N/A"};
        final Exception[] asyncException = new Exception[1];

        speedTestSocket.addSpeedTestListener(new ISpeedTestListener() {
            @Override
            public void onCompletion(SpeedTestReport report) {
                try {
                    BigDecimal transferRateBit = report.getTransferRateBit();
                    double mbps = transferRateBit.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP).doubleValue();
                    result[0] = String.format("%.2f", mbps);
                } catch (Exception e) {
                    asyncException[0] = e;
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onError(SpeedTestError speedTestError, String errorMessage) {
                asyncException[0] = new Exception("上傳測試錯誤: " + errorMessage);
                latch.countDown();
            }

            @Override
            public void onProgress(float percent, SpeedTestReport report) {
                // 進度監控
            }
        });

        // 上傳 5MB 的數據（增加測試準確度）
        speedTestSocket.startUpload(serverUrl, 5000000);

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            speedTestSocket.forceStopTask();
            throw new Exception("上傳測試超時");
        }

        if (asyncException[0] != null) {
            throw asyncException[0];
        }

        return result[0];
    }

    /**
     * 取得伺服器顯示名稱
     */
    private String getServerDisplayName(String url) {
        if (url.contains("hinet.net")) return "台灣 (中華電信 HiNet)";
        if (url.contains("thinkbroadband")) return "英國 (ThinkBroadband)";
        if (url.contains("ovh")) return "全球 (OVH)";
        if (url.contains("tele2")) return "瑞典 (Tele2)";
        if (url.contains("bouygues.testdebit")) return "法國 (Bouygues)";
        if (url.contains("ikoula.testdebit")) return "法國 (Ikoula)";
        return url;
    }

    /**
     * 找到延遲最低的伺服器
     */
    private String findBestServer() {
        String bestServer = null;
        double minLatency = Double.MAX_VALUE;

        for (String server : TEST_SERVERS) {
            try {
                double latency = measureLatency(server);
                if (latency < minLatency && latency > 0 && latency < 5000) {
                    minLatency = latency;
                    bestServer = server;
                }
            } catch (Exception e) {
                // 該伺服器無法連接，嘗試下一個
            }
        }

        return bestServer;
    }

    /**
     * 驗證伺服器是否可用（測試下載）
     */
    private boolean isServerAvailable(String serverUrl) {
        try {
            java.net.URL url = new java.net.URL(serverUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 測量延遲
     */
    private double measureLatency(String urlString) throws Exception {
        java.net.URL url = new java.net.URL(urlString);
        long startTime = System.currentTimeMillis();

        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setRequestMethod("HEAD");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        int responseCode = connection.getResponseCode();
        long endTime = System.currentTimeMillis();

        connection.disconnect();

        if (responseCode >= 200 && responseCode < 400) {
            return (double) (endTime - startTime);
        } else {
            throw new Exception("無法連接");
        }
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
