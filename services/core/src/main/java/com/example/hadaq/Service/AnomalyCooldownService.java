package com.example.hadaq.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// at most one moisture_anomaly per sensor per cooldown (hadaq.anomaly-cooldown-seconds). demo_reset clears it
@Service
public class AnomalyCooldownService {

    private final long cooldownMillis;
    private final Map<String, Long> lastAnomaly = new ConcurrentHashMap<>();

    public AnomalyCooldownService(@Value("${hadaq.anomaly-cooldown-seconds:600}") long cooldownSeconds) {
        this.cooldownMillis = cooldownSeconds * 1000;
    }

    // true = the sensor may raise an anomaly now (and the cooldown starts)
    public boolean tryFire(String sensorId) {
        long now = System.currentTimeMillis();
        boolean[] fired = {false};
        lastAnomaly.compute(sensorId, (id, last) -> {
            if (last == null || now - last > cooldownMillis) {
                fired[0] = true;
                return now;
            }
            return last;
        });
        return fired[0];
    }

    public void clear() {
        lastAnomaly.clear();
    }
}
