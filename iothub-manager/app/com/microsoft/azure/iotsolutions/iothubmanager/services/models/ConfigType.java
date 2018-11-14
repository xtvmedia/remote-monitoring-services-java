// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.iothubmanager.services.models;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ConfigType {
    firmwareUpdateMxChip("FirmwareUpdateMxChip"),
    custom("Custom"),
    edge("Edge");

    private final String value;

    ConfigType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public String toString() {
        return getValue();
    }
}