package com.bko.bpmn_engine.api.dto;

import java.util.List;
import java.util.Map;

public record AiChatRequest(
        String conversationId,
        String route,
        Map<String, Object> context,
        List<ChatMessageDto> messages
) {
}
