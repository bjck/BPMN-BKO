package com.bko.bpmn_engine.api.dto;

import java.util.Map;

public record TriggerCatchByMessageRefRequest(
        String messageRef,
        String correlationKey,
        Map<String, Object> variables
) {
}
