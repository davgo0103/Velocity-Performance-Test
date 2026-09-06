package com.serverperf;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** 純顯示層的格式化工具：量測層一律回傳型別化數值，字串只在這裡產生。 */
final class Format {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
    private static final int BAR_WIDTH = 20;
    private static final String PLACEHOLDER = "N/A";

    private Format() {
    }

    static String bytes(long value) {
        if (value < 0) {
            return PLACEHOLDER;
        }
        double scaled = value;
        int unit = 0;
        while (scaled >= 1024 && unit < UNITS.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return unit == 0
                ? "%d %s".formatted(value, UNITS[unit])
                : "%.2f %s".formatted(scaled, UNITS[unit]);
    }

    static String bytes(OptionalLong value) {
        return value.isPresent() ? bytes(value.getAsLong()) : PLACEHOLDER;
    }

    static String mbps(OptionalDouble value) {
        return value.isPresent() ? "%.2f Mbps".formatted(value.getAsDouble()) : PLACEHOLDER;
    }

    static String millis(double value) {
        return "%.1f ms".formatted(value);
    }

    static String percent(double ratio) {
        return "%.1f%%".formatted(ratio * 100);
    }

    static String percent(OptionalDouble ratio) {
        return ratio.isPresent() ? percent(ratio.getAsDouble()) : PLACEHOLDER;
    }

    static double ratio(long used, long total) {
        return total > 0 ? Math.clamp((double) used / total, 0.0, 1.0) : 0.0;
    }

    /** 以方塊字元畫出使用率長條，並依水位切換顏色（回傳 MiniMessage 標記）。 */
    static String bar(double ratio) {
        double clamped = Math.clamp(ratio, 0.0, 1.0);
        int filled = (int) Math.round(clamped * BAR_WIDTH);
        String colour = clamped < 0.60 ? "green" : clamped < 0.85 ? "yellow" : "red";
        return "<%s>%s</%s><dark_gray>%s</dark_gray>".formatted(
                colour,
                "█".repeat(filled),
                colour,
                "░".repeat(BAR_WIDTH - filled));
    }

    static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainder = seconds % 60;

        if (days > 0) {
            return "%d 天 %d 小時 %d 分".formatted(days, hours, minutes);
        }
        if (hours > 0) {
            return "%d 小時 %d 分".formatted(hours, minutes);
        }
        if (minutes > 0) {
            return "%d 分 %d 秒".formatted(minutes, remainder);
        }
        return "%.1f 秒".formatted(duration.toMillis() / 1000.0);
    }

    static String count(int value, String unit) {
        return "%d %s".formatted(value, unit);
    }

    /** 語系無關的小寫轉換，避免土耳其語環境把 I 轉成 ı。 */
    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static String placeholder() {
        return PLACEHOLDER;
    }
}
