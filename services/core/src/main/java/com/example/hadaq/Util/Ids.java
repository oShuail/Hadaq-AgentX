package com.example.hadaq.Util;

import java.util.UUID;

// generated IDs: prefix + 12 hex characters, e.g. evt_3f9a1c2b7d4e (contracts/README.md)
public final class Ids {

    private Ids() {
    }

    public static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
