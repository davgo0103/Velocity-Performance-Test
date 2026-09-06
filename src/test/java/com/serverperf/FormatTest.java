package com.serverperf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FormatTest {

    @Test
    @DisplayName("位元組換算到合適的單位")
    void formatsBytes() {
        assertEquals("512 B", Format.bytes(512));
        assertEquals("1.00 KiB", Format.bytes(1024));
        assertEquals("1.50 MiB", Format.bytes(1024 * 1536));
        assertEquals("N/A", Format.bytes(OptionalLong.empty()));
        assertEquals("1.00 GiB", Format.bytes(OptionalLong.of(1024L * 1024 * 1024)));
    }

    @Test
    @DisplayName("缺值一律顯示 N/A，而不是 0")
    void missingMeasurementsRenderAsPlaceholder() {
        assertEquals("N/A", Format.mbps(OptionalDouble.empty()));
        assertEquals("N/A", Format.percent(OptionalDouble.empty()));
        assertEquals("935.42 Mbps", Format.mbps(OptionalDouble.of(935.4157)));
    }

    @Test
    @DisplayName("使用率長條的寬度與顏色隨水位變化")
    void barReflectsRatio() {
        assertTrue(Format.bar(0.0).contains("<green>"));
        assertTrue(Format.bar(0.5).contains("<green>"));
        assertTrue(Format.bar(0.7).contains("<yellow>"));
        assertTrue(Format.bar(0.95).contains("<red>"));

        // 超出範圍的輸入不應產生負數長度的字串。
        assertTrue(Format.bar(-1).contains("░"));
        assertTrue(Format.bar(2).contains("█"));
    }

    @Test
    @DisplayName("總量為 0 時不應除以零")
    void ratioHandlesZeroTotal() {
        assertEquals(0.0, Format.ratio(10, 0));
        assertEquals(0.5, Format.ratio(50, 100));
        assertEquals(1.0, Format.ratio(200, 100));
    }

    @Test
    @DisplayName("時間長度依量級選用不同單位")
    void formatsDuration() {
        assertEquals("2.5 秒", Format.duration(Duration.ofMillis(2500)));
        assertEquals("3 分 20 秒", Format.duration(Duration.ofSeconds(200)));
        assertEquals("2 小時 5 分", Format.duration(Duration.ofMinutes(125)));
        assertEquals("1 天 3 小時 0 分", Format.duration(Duration.ofHours(27)));
    }

    @Test
    @DisplayName("小寫轉換不受系統語系影響")
    void lowerIsLocaleIndependent() {
        var original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
            assertEquals("linux", Format.lower("LINUX"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
