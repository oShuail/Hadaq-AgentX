package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.DeviceDTOOut;
import com.example.hadaq.DTOOut.MqttStatusDTOOut;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Enum.DeviceStatus;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

// sensor nodes (real or simulated): MQTT online/offline (retained status, last will) and when they last sent data
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    public static final String SIM_DEVICE = "sim-01";

    private final RegistryService registryService;
    private final ReadingService readingService;
    private final SensorHealthService sensorHealthService;

    private final Map<String, MqttStatusDTOOut> mqttStatus = new ConcurrentHashMap<>();

    // hadaq/v1/{building}/devices/{device}/status: "online" / "offline"
    public void handleStatus(String deviceId, String status) {
        log.info("device {} is {}", deviceId, status);
        mqttStatus.put(deviceId, new MqttStatusDTOOut(status, Times.now()));
    }

    public List<DeviceDTOOut> devices(String buildingId) {
        registryService.check(buildingId);
        Instant now = Times.now();
        Map<String, List<String>> byDevice = new TreeMap<>();
        for (SensorSpec spec : registryService.sensors()) {
            byDevice.computeIfAbsent(spec.deviceId() == null ? "unknown" : spec.deviceId(), k -> new ArrayList<>()).add(spec.id());
        }
        List<DeviceDTOOut> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byDevice.entrySet()) {
            String deviceId = entry.getKey();
            List<SensorReading> latest = readingService.latest(entry.getValue());
            Instant last = latest.stream().map(SensorReading::getTs).max(Comparator.naturalOrder()).orElse(null);
            Double age = last == null ? null : Duration.between(last, now).toNanos() / 1e9;
            boolean reporting = age != null && age <= sensorHealthService.getStaleAfterSeconds();
            MqttStatusDTOOut mqtt = mqttStatus.get(deviceId);
            // a last will ("offline") newer than the last reading means the node just dropped off
            boolean dropped = mqtt != null && "offline".equals(mqtt.status()) && (last == null || mqtt.ts().isAfter(last));
            DeviceStatus status = reporting && !dropped ? DeviceStatus.ONLINE : last != null ? DeviceStatus.OFFLINE : DeviceStatus.NEVER_SEEN;
            List<String> provenance = latest.stream().map(r -> r.getProvenance().value()).filter(Objects::nonNull)
                    .distinct().sorted().toList();
            out.add(new DeviceDTOOut(deviceId, status, entry.getValue(), last,
                    age == null ? null : Numbers.round(age, 1), mqtt, provenance));
        }
        return out;
    }

    // the sensor simulator is sending: over MQTT (retained status) or HTTP (fresh readings)
    public boolean simOnline(String buildingId) {
        return devices(buildingId).stream()
                .anyMatch(d -> SIM_DEVICE.equals(d.deviceId()) && d.status() == DeviceStatus.ONLINE);
    }
}
