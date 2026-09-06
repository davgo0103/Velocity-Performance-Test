package com.serverperf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moandjiezana.toml.Toml;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PerfConfigTest {

    @Test
    @DisplayName("JAR 內建的 config.toml 必須能解析")
    void bundledDefaultsParse() {
        PerfConfig config = PerfConfig.defaults();

        assertFalse(config.downloadTargets().isEmpty());
        assertTrue(config.downloadBytes() > 0);
        assertTrue(config.uploadEnabled());
        assertTrue(config.pingSamples() >= 1);
        assertTrue(config.parallelStreams() >= 1);
        assertFalse(config.warmup().isNegative());
        assertFalse(config.publicIpServices().isEmpty());
    }

    @Test
    @DisplayName("{bytes} 佔位符會被替換成實際要求的位元組數")
    void targetResolvesByteplaceholder() {
        var target = new PerfConfig.Target("CF", "https://example.invalid/__down?bytes={bytes}");

        assertEquals("https://example.invalid/__down?bytes=1024", target.resolveUrl(1024));
        assertEquals("https://example.invalid/__down?bytes=0", target.resolveUrl(0));
    }

    @Test
    @DisplayName("沒有佔位符的節點 URL 保持原樣")
    void targetWithoutPlaceholderIsUnchanged() {
        var target = new PerfConfig.Target("Static", "https://example.invalid/100MB.bin");

        assertEquals("https://example.invalid/100MB.bin", target.resolveUrl(50_000_000));
    }

    @Test
    @DisplayName("拒絕非 http(s) 或空白的節點設定")
    void targetValidatesInput() {
        assertThrows(IllegalArgumentException.class, () -> new PerfConfig.Target("x", "ftp://a/b"));
        assertThrows(IllegalArgumentException.class, () -> new PerfConfig.Target("  ", "https://a/b"));
        assertThrows(IllegalArgumentException.class, () -> new PerfConfig.Target("x", "   "));
    }

    @Test
    @DisplayName("缺少的欄位會退回預設值，MiB 會換算成位元組")
    void missingKeysFallBackToDefaults() {
        PerfConfig config = PerfConfig.parse(new Toml().read("""
                [network]
                download-mib = 8

                [[network.download-targets]]
                name = "Only"
                url = "https://example.invalid/file.bin"
                """));

        assertEquals(8L * 1024 * 1024, config.downloadBytes());
        assertEquals(25L * 1024 * 1024, config.uploadBytes());
        assertEquals(Duration.ofSeconds(5), config.connectTimeout());
        assertEquals(List.of(), config.publicIpServices());
        assertEquals(1, config.downloadTargets().size());
        assertEquals(4, config.parallelStreams());
        assertEquals(Duration.ofMillis(1_000), config.warmup());
    }

    @Test
    @DisplayName("parallel-streams 會被夾在 1..16，避免無意義的連線數")
    void parallelStreamsAreClamped() {
        String base = """
                [network]
                parallel-streams = %d

                [[network.download-targets]]
                name = "Only"
                url = "https://example.invalid/file.bin"
                """;

        assertEquals(1, PerfConfig.parse(new Toml().read(base.formatted(0))).parallelStreams());
        assertEquals(1, PerfConfig.parse(new Toml().read(base.formatted(-5))).parallelStreams());
        assertEquals(8, PerfConfig.parse(new Toml().read(base.formatted(8))).parallelStreams());
        assertEquals(16, PerfConfig.parse(new Toml().read(base.formatted(999))).parallelStreams());
    }

    @Test
    @DisplayName("warmup-millis = 0 是合法的（等於停用暖機期排除）")
    void warmupCanBeDisabled() {
        PerfConfig config = PerfConfig.parse(new Toml().read("""
                [network]
                warmup-millis = 0

                [[network.download-targets]]
                name = "Only"
                url = "https://example.invalid/file.bin"
                """));

        assertEquals(Duration.ZERO, config.warmup());
    }

    @Test
    @DisplayName("upload-mib = 0 或空白 URL 會停用上傳測試")
    void uploadCanBeDisabled() {
        String base = """
                [network]
                %s

                [[network.download-targets]]
                name = "Only"
                url = "https://example.invalid/file.bin"
                """;

        assertFalse(PerfConfig.parse(new Toml().read(base.formatted("upload-mib = 0"))).uploadEnabled());
        assertFalse(PerfConfig.parse(new Toml().read(base.formatted("upload-url = \"\""))).uploadEnabled());
        assertTrue(PerfConfig.parse(new Toml().read(
                base.formatted("upload-url = \"https://example.invalid/up\""))).uploadEnabled());
    }

    @Test
    @DisplayName("沒有任何測速節點時應直接拒絕，而不是產生無法使用的設定")
    void rejectsConfigWithoutTargets() {
        assertThrows(IllegalArgumentException.class, () -> PerfConfig.parse(new Toml().read("""
                [network]
                download-mib = 8
                """)));
    }

    @Test
    @DisplayName("缺少 [network] 區段時應拋出可讀的錯誤")
    void rejectsMissingNetworkSection() {
        assertThrows(IllegalArgumentException.class, () -> PerfConfig.parse(new Toml().read("")));
    }
}
