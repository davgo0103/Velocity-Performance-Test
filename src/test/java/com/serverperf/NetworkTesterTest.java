package com.serverperf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 針對吞吐量統計的測試：關鍵在於哪些時間「不該」被算進傳輸時間。 */
class NetworkTesterTest {

    private static final long MS = 1_000_000L;
    private static final long WARMUP = 1_000 * MS;

    /** 產生取樣序列：samples[i] = {i * 100ms, bytes[i]}。 */
    private static List<long[]> samples(long... bytes) {
        List<long[]> result = new ArrayList<>(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            result.add(new long[] {i * 100 * MS, bytes[i]});
        }
        return result;
    }

    private static double mbps(Optional<NetworkTester.Transfer> transfer) {
        return transfer.orElseThrow().mbps();
    }

    @Test
    @DisplayName("穩定速率下，估算值等於實際速率")
    void steadyRateIsReportedAsIs() {
        // 每 100 ms 傳 1_250_000 bytes = 100 Mbps，跑滿 3 秒。
        long[] bytes = new long[31];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = i * 1_250_000L;
        }

        assertEquals(100.0, mbps(NetworkTester.summarise(samples(bytes), WARMUP)), 0.001);
    }

    @Test
    @DisplayName("開頭的 TTFB 空窗不算進傳輸時間")
    void leadingIdleSamplesAreExcluded() {
        // 前 500 ms 完全沒有資料（連線建立 + TTFB），之後 500 ms 內傳完 12_500_000 bytes。
        // 若把空窗算進去會得到 100 Mbps；正確答案是 200 Mbps。
        List<long[]> samples = samples(0, 0, 0, 0, 0, 2_500_000, 5_000_000, 7_500_000, 10_000_000, 12_500_000);

        assertEquals(200.0, mbps(NetworkTester.summarise(samples, 0)), 0.001);
    }

    @Test
    @DisplayName("慢啟動的爬升期被排除後，回報的是穩態速率而非整段平均")
    void slowStartIsExcluded() {
        // 前 1 秒從 0 爬到滿速（累計 6_250_000 bytes），之後 2 秒穩定在 200 Mbps。
        List<long[]> samples = new ArrayList<>();
        long total = 0;
        for (int i = 0; i <= 30; i++) {
            if (i > 0) {
                double fraction = Math.min(1.0, i / 10.0);
                total += (long) (2_500_000 * fraction);
            }
            samples.add(new long[] {i * 100L * MS, total});
        }

        double naive = (total * 8.0) / 3.0 / 1_000_000.0;
        double windowed = mbps(NetworkTester.summarise(samples, WARMUP));

        assertEquals(200.0, windowed, 1.0);
        // 整段平均會被慢啟動拉低，這正是舊算法低估的來源。
        assertTrue(naive < windowed - 10, "整段平均 %.1f 應明顯低於穩態 %.1f".formatted(naive, windowed));
    }

    @Test
    @DisplayName("暖機期上限為傳輸時長的三分之一，短測試才不會沒有量測窗")
    void warmupIsCappedForShortTransfers() {
        // 總時長只有 600 ms，遠短於 1 秒的暖機設定；若照單全收就沒有窗可算。
        List<long[]> samples = samples(0, 1_250_000, 2_500_000, 3_750_000, 5_000_000, 6_250_000, 7_500_000);

        assertEquals(100.0, mbps(NetworkTester.summarise(samples, WARMUP)), 0.001);
    }

    @Test
    @DisplayName("沒有任何位元組時回報失敗，而不是算出 0 Mbps")
    void emptyTransferHasNoResult() {
        assertTrue(NetworkTester.summarise(samples(0, 0, 0), WARMUP).isEmpty());
        assertTrue(NetworkTester.summarise(samples(0), WARMUP).isEmpty());
        assertTrue(NetworkTester.summarise(List.of(), WARMUP).isEmpty());
    }

    @Test
    @DisplayName("回報的位元組數是實際傳輸總量，不受量測窗影響")
    void reportedBytesCoverTheWholeTransfer() {
        List<long[]> samples = samples(0, 0, 1_000_000, 2_000_000, 3_000_000, 4_000_000);

        assertEquals(4_000_000L, NetworkTester.summarise(samples, WARMUP).orElseThrow().bytes());
    }
}
