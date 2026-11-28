package com.example.hadaq.Util;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Enum.Role;

import java.util.Arrays;
import java.util.stream.Collectors;

/*
 * The X-Hadaq-User header (contracts v2): surveyor | conservator | site_manager. Hackathon only, no real auth:
 * it tells the API who is acting, and only the routes that need a role check it.
 */
public final class Roles {

    public static final String HEADER = "X-Hadaq-User";

    private Roles() {
    }

    // 403 when the header is missing or names another role, 400 when it is not a known role
    public static Role require(String header, Role... allowed) {
        String names = Arrays.stream(allowed).map(Role::value).collect(Collectors.joining(" or "));
        if (header == null || header.isBlank()) {
            throw new ApiException(403, HEADER + " header required (" + names + ")");
        }
        Role role;
        try {
            role = Role.fromValue(header.trim());
        } catch (ApiException unknown) {
            throw new ApiException(400, HEADER + " must be surveyor, conservator or site_manager");
        }
        if (Arrays.stream(allowed).noneMatch(r -> r == role)) {
            throw new ApiException(403, "Only the " + names + " can do this (" + HEADER + ": " + role.value() + ")");
        }
        return role;
    }
}
