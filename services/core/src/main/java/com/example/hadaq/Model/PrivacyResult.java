package com.example.hadaq.Model;

// checked = false when the bytes could not be decoded as an image (stored unchanged)
public record PrivacyResult(byte[] data, boolean checked, int facesHidden) {
}
