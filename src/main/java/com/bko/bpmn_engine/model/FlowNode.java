package com.bko.bpmn_engine.model;

/**
 * Sealed interface representing BPMN flow nodes.
 */
public sealed interface FlowNode permits StartEvent, EndEvent, ServiceTask, UserTask,
        ExclusiveGateway, ParallelGateway, InclusiveGateway, ComplexGateway, EventBasedGateway,
        IntermediateCatchEvent, IntermediateThrowEvent {

    String id();

    String name();
}
