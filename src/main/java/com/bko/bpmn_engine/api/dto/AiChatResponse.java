package com.bko.bpmn_engine.api.dto;

import java.util.Map;

public record AiChatResponse(
        String conversationId,
        String model,
        boolean providerConfigured,
        ChatMessageDto reply,
        Map<String, Object> usage,
        AiDiagramUpdateDto diagramUpdate
) {
}
