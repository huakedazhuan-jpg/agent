package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CommandRequest(
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("Allowed local command to execute.")
        String command
) {
}
