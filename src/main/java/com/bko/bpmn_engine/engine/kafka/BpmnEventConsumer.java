package com.bko.bpmn_engine.engine.kafka;

import com.bko.bpmn_engine.engine.ProcessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes BPMN events from Kafka and triggers message start or catch events in the process engine.
 */
@Component
@ConditionalOnProperty(prefix = "bpmn.kafka", name = "enabled", havingValue = "true")
public class BpmnEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BpmnEventConsumer.class);

    private final ProcessEngine processEngine;

    public BpmnEventConsumer(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    @KafkaListener(
            topics = "${bpmn.kafka.topic:bpmn-events}",
            groupId = "${bpmn.kafka.consumer-group:bpmn-engine}",
            containerFactory = "bpmnEventKafkaListenerContainerFactory"
    )
    public void onBpmnEvent(
            @Payload BpmnEventPayload payload,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        if (payload == null) return;
        log.debug("Received BPMN event key={} payload={}", key, payload);
        log.trace("BPMN event consume key={} messageRef={} signalRef={} instanceId={} nodeId={} processDefinitionId={} correlationKey={}",
                key, payload.messageRef(), payload.signalRef(), payload.instanceId(), payload.nodeId(),
                payload.processDefinitionId(), payload.correlationKey());

        if (handleMessageStart(payload)) return;
        if (handleCatchEvent(payload)) return;
        handleCatchByMessageRef(payload);
    }

    private boolean handleMessageStart(BpmnEventPayload payload) {
        if (payload.instanceId() != null || (payload.messageRef() == null && payload.processDefinitionId() == null)) {
            return false;
        }
        String processDefinitionId = payload.processDefinitionId();
        String messageRef = payload.messageRef();
        if (processDefinitionId == null && messageRef != null) {
            var defIds = processEngine.getProcessDefinitionIdsByMessageRef(messageRef);
            if (!defIds.isEmpty()) processDefinitionId = defIds.getFirst();
        }
        if (processDefinitionId != null) {
            log.trace("BPMN event triggering message start processDefinitionId={} messageRef={} correlationKey={}", processDefinitionId, messageRef, payload.correlationKey());
            processEngine.triggerMessageStart(
                    processDefinitionId,
                    messageRef != null ? messageRef : "",
                    payload.correlationKey(),
                    payload.variables() != null ? payload.variables() : Map.of());
        }
        return true;
    }

    private boolean handleCatchEvent(BpmnEventPayload payload) {
        if (payload.instanceId() == null || payload.nodeId() == null) {
            return false;
        }
        log.trace("BPMN event triggering catch event instanceId={} nodeId={}", payload.instanceId(), payload.nodeId());
        processEngine.triggerCatchEvent(
                payload.instanceId(),
                payload.nodeId(),
                payload.variables() != null ? payload.variables() : Map.of());
        return true;
    }

    private void handleCatchByMessageRef(BpmnEventPayload payload) {
        if (payload.messageRef() == null && payload.signalRef() == null) return;
        String ref = payload.messageRef() != null ? payload.messageRef() : payload.signalRef();
        log.trace("BPMN event triggering catch by messageRef ref={} correlationKey={}", ref, payload.correlationKey());
        processEngine.triggerCatchEventByMessageRef(
                ref,
                payload.correlationKey(),
                payload.variables() != null ? payload.variables() : Map.of());
    }
}
