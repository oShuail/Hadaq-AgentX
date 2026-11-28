package com.example.hadaq.Events;

import com.example.hadaq.DTOOut.EventDTOOut;

// published inside the app every time a new event is stored (from HTTP, MQTT or the app itself).
// The demo feed, camera and anything else that reacts to scenario events listens to it with @EventListener
public record HadaqEventStored(EventDTOOut event) {
}
