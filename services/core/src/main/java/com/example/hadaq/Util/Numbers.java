package com.example.hadaq.Util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Numbers {

    private Numbers() {
    }

    // like Python round(x, digits): half to even
    public static double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
