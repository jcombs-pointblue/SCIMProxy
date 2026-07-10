// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta.map;

/** Okta user status <-> SCIM active mapping. */
public final class OktaStatus {

    private OktaStatus() {}

    public static boolean isActive(String status) {
        return switch (status) {
            case "STAGED", "DEPROVISIONED", "SUSPENDED" -> false;
            default -> true; // ACTIVE, PROVISIONED, RECOVERY, PASSWORD_EXPIRED, LOCKED_OUT
        };
    }
}
