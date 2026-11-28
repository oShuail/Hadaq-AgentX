package com.example.hadaq.Api;

// what every browser gets on /ws/live: {"channel": "reading" | "event" | "photo" | "detection" | "simulation" | "risk", "data": {...}}
public record LiveMessage(String channel, Object data) {
}
