// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.devicetelemetry.webservice.v1.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.azure.iotsolutions.devicetelemetry.services.exceptions.InvalidInputException;
import com.microsoft.azure.iotsolutions.devicetelemetry.services.models.actions.EmailActionServiceModel;
import com.microsoft.azure.iotsolutions.devicetelemetry.services.models.actions.IActionServiceModel;

import java.util.Map;
import java.util.TreeMap;

public class ActionApiModel {

    private String type;

    private Map<String, Object> parameters;

    public ActionApiModel(String type, Map<String, Object> parameters) throws InvalidInputException {
        this.type = type;
        try {
            this.parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.parameters.putAll(parameters);
        } catch (Exception e) {
            String message = String.format(
                    "Error, duplicate parameters provided for the %s action. Parameters are case insensitive",
                    type);
            throw new InvalidInputException(message);
        }
    }

    public ActionApiModel(IActionServiceModel action) {
        this();
        if (action != null) {
            this.type = action.getType().toString();
            this.parameters = action.getParameters();
        }
    }

    public ActionApiModel() {
        this.type = IActionServiceModel.ActionType.Email.toString();
        this.parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    @JsonProperty("Type")
    public String getType() { return this.type; }

    @JsonProperty("Parameters")
    public Map<String, Object> getParameters() { return this.parameters; }

    public void setType(String type) { this.type = type; }

    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    public IActionServiceModel toServiceModel() throws InvalidInputException {
        IActionServiceModel.ActionType actionType;
        try {
            actionType = IActionServiceModel.ActionType.valueOf(this.type);
        } catch (Exception e) {
            throw new InvalidInputException();
        }

        switch (actionType) {
            case Email:
                return new EmailActionServiceModel(this.parameters);
            default:
                throw new InvalidInputException("");
        }
    }
}