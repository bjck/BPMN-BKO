package com.bko.bpmn_engine.api.dto;

public record AiDiagramUpdateDto(
        String mode,
        String anchorElementId,
        String bpmnXml,
        String summary
) {
}
