package com.bko.bpmn_engine.parser;

import com.bko.bpmn_engine.model.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * BPMN 2.0 XML parser producing a CompiledProcess.
 * Uses javax.xml.parsers.DocumentBuilder — no third-party XML libraries.
 */
@Component
public class BpmnParser {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String ENGINE_NS = "https://bko.dev/schema/bpmn-engine/1.0";

    private static final String SEQUENCE_FLOW = "sequenceFlow";
    private static final String MESSAGE_REF = "messageRef";
    private static final String MESSAGE_EVENT_DEFINITION = "messageEventDefinition";
    private static final String TIMER_EVENT_DEFINITION = "timerEventDefinition";
    private static final String EXTENSION_ELEMENTS = "extensionElements";
    private static final String TASK_CONFIGURATION = "taskConfiguration";
    private static final String WORKER = "worker";
    private static final String RESULT_VARIABLE = "resultVariable";
    private static final String DEFAULT = "default";

    private final DocumentBuilder documentBuilder;

    public BpmnParser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setIgnoringElementContentWhitespace(true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to create DocumentBuilder", e);
        }
    }

    /**
     * Parses BPMN 2.0 XML and produces a CompiledProcess.
     *
     * @param bpmnXml the BPMN 2.0 XML string
     * @return compiled process with adjacency and sequential chains
     * @throws BpmnParseException if parsing fails
     */
    public CompiledProcess parse(String bpmnXml) throws BpmnParseException {
        Document doc;
        try {
            doc = documentBuilder.parse(new InputSource(new StringReader(bpmnXml)));
        } catch (SAXException e) {
            throw new BpmnParseException("Invalid XML: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new BpmnParseException("Failed to read BPMN XML: " + e.getMessage(), e);
        }

        NodeList processList = doc.getElementsByTagNameNS(BPMN_NS, "process");
        if (processList.getLength() == 0) {
            processList = doc.getElementsByTagName("process");
        }
        if (processList.getLength() == 0) {
            throw new BpmnParseException("No process element found in BPMN XML");
        }

        Element process = (Element) processList.item(0);
        String processId = getAttr(process, "id", "Process_1");
        String processName = getAttr(process, "name", "");

        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        Map<String, SequenceFlow> sequenceFlows = new LinkedHashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        // First pass: collect all flow elements to resolve references
        List<Element> flowElements = new ArrayList<>();
        NodeList childNodes = process.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node n = childNodes.item(i);
            if (n instanceof Element e) {
                String local = e.getLocalName();
                if (local != null && local.equals(SEQUENCE_FLOW)) {
                    flowElements.add(e);
                }
            }
        }

        // Parse sequence flows first (needed for incoming/outgoing)
        for (Element flowEl : flowElements) {
            String flowId = requireAttr(flowEl, "id", SEQUENCE_FLOW);
            String sourceRef = requireAttr(flowEl, "sourceRef", flowId);
            String targetRef = requireAttr(flowEl, "targetRef", flowId);
            ExpressionDefinition condition = getConditionExpression(flowEl);
            sequenceFlows.put(flowId, new SequenceFlow(flowId, sourceRef, targetRef, condition.expression(), condition.language()));
            adjacency.computeIfAbsent(sourceRef, k -> new ArrayList<>()).add(targetRef);
        }

        // Build incoming/outgoing maps per node
        Map<String, List<String>> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (SequenceFlow sf : sequenceFlows.values()) {
            outgoing.computeIfAbsent(sf.sourceRef(), k -> new ArrayList<>()).add(sf.targetRef());
            incoming.computeIfAbsent(sf.targetRef(), k -> new ArrayList<>()).add(sf.sourceRef());
        }

        // Second pass: parse flow nodes
        String startNodeId = null;
        List<String> endNodeIds = new ArrayList<>();

        for (int i = 0; i < childNodes.getLength(); i++) {
            Node n = childNodes.item(i);
            if (!(n instanceof Element e)) continue;
            String local = e.getLocalName();
            if (local == null) continue;

            switch (local) {
                case "startEvent" -> {
                    FlowNode node = parseStartEvent(e, outgoing);
                    nodes.put(node.id(), node);
                    if (startNodeId == null) startNodeId = node.id();
                }
                case "endEvent" -> {
                    FlowNode node = parseEndEvent(e, incoming);
                    nodes.put(node.id(), node);
                    endNodeIds.add(node.id());
                }
                case "serviceTask" -> {
                    FlowNode node = parseServiceTask(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "userTask" -> {
                    FlowNode node = parseUserTask(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "exclusiveGateway" -> {
                    FlowNode node = parseExclusiveGateway(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "parallelGateway" -> {
                    FlowNode node = parseParallelGateway(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "inclusiveGateway" -> {
                    FlowNode node = parseInclusiveGateway(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "complexGateway" -> {
                    FlowNode node = parseComplexGateway(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "eventBasedGateway" -> {
                    FlowNode node = parseEventBasedGateway(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "intermediateCatchEvent" -> {
                    FlowNode node = parseIntermediateCatchEvent(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case "intermediateThrowEvent" -> {
                    FlowNode node = parseIntermediateThrowEvent(e, incoming, outgoing);
                    nodes.put(node.id(), node);
                }
                case SEQUENCE_FLOW -> { /* already handled */ }
                default -> { /* ignore other elements */ }
            }
        }

        if (startNodeId == null) {
            throw new BpmnParseException("No startEvent found in process");
        }

        ProcessDefinition definition = new ProcessDefinition(
                processId,
                processName,
                Map.copyOf(nodes),
                Map.copyOf(sequenceFlows),
                startNodeId,
                List.copyOf(endNodeIds)
        );

        List<List<String>> sequentialChains = CompiledProcess.detectSequentialChains(definition, adjacency);

        return new CompiledProcess(definition, adjacency, sequentialChains);
    }

    private static String getAttr(Element el, String name, String defaultValue) {
        String v = el.getAttribute(name);
        if (v == null || v.isBlank()) return defaultValue;
        return v.trim();
    }

    private static String requireAttr(Element el, String name, String contextId) throws BpmnParseException {
        String v = el.getAttribute(name);
        if (v == null || v.isBlank()) {
            throw new BpmnParseException(contextId, "Missing required attribute: " + name);
        }
        return v.trim();
    }

    private static ExpressionDefinition getConditionExpression(Element flowEl) {
        NodeList nl = flowEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && "conditionExpression".equals(e.getLocalName())) {
                String expression = e.getTextContent() != null ? e.getTextContent().trim() : "";
                String language = getAttr(e, "language", null);
                return new ExpressionDefinition(expression, language);
            }
        }
        return new ExpressionDefinition(null, null);
    }

    private static String getAssignee(Element el) {
        String v = el.getAttributeNS(CAMUNDA_NS, "assignee");
        if (v == null || v.isBlank()) {
            v = el.getAttribute("assignee");
        }
        return v != null ? v.trim() : null;
    }

    private FlowNode parseStartEvent(Element e, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "startEvent");
        String name = getAttr(e, "name", "");
        List<String> out = outgoing.getOrDefault(id, List.of());
        StartEventTrigger trigger = StartEventTrigger.NONE;
        String messageRef = getAttrNS(e, ENGINE_NS, MESSAGE_REF);
        if (messageRef == null && findDirectChild(e, BPMN_NS, MESSAGE_EVENT_DEFINITION) != null) {
            Element msgDef = findDirectChild(e, BPMN_NS, MESSAGE_EVENT_DEFINITION);
            messageRef = msgDef != null ? getAttr(msgDef, MESSAGE_REF, getAttrNS(e, ENGINE_NS, MESSAGE_REF)) : getAttrNS(e, ENGINE_NS, MESSAGE_REF);
        }
        String timerDefinition = getAttrNS(e, ENGINE_NS, "timerDefinition");
        if (timerDefinition == null) {
            Element timerDef = findDirectChild(e, BPMN_NS, TIMER_EVENT_DEFINITION);
            if (timerDef != null) {
                Element timeCycle = findDirectChild(timerDef, BPMN_NS, "timeCycle");
                Element timeDuration = findDirectChild(timerDef, BPMN_NS, "timeDuration");
                if (timeCycle != null && timeCycle.getTextContent() != null && !timeCycle.getTextContent().isBlank())
                    timerDefinition = timeCycle.getTextContent().trim();
                else if (timeDuration != null && timeDuration.getTextContent() != null && !timeDuration.getTextContent().isBlank())
                    timerDefinition = timeDuration.getTextContent().trim();
            }
        }
        if (messageRef != null && !messageRef.isBlank()) trigger = StartEventTrigger.MESSAGE;
        else if (timerDefinition != null && !timerDefinition.isBlank()) trigger = StartEventTrigger.TIMER;
        return new StartEvent(id, name, out, trigger, messageRef, timerDefinition);
    }

    private FlowNode parseEndEvent(Element e, Map<String, List<String>> incoming) throws BpmnParseException {
        String id = requireAttr(e, "id", "endEvent");
        String name = getAttr(e, "name", "");
        List<String> in = incoming.getOrDefault(id, List.of());
        EndEventType endType = EndEventType.NONE;
        String messageRef = getAttrNS(e, ENGINE_NS, MESSAGE_REF);
        if (messageRef == null && findDirectChild(e, BPMN_NS, MESSAGE_EVENT_DEFINITION) != null) {
            Element msgDef = findDirectChild(e, BPMN_NS, MESSAGE_EVENT_DEFINITION);
            if (msgDef != null) messageRef = getAttr(msgDef, MESSAGE_REF, null);
        }
        String errorCode = getAttrNS(e, ENGINE_NS, "errorCode");
        if (errorCode == null && findDirectChild(e, BPMN_NS, "errorEventDefinition") != null) {
            Element errDef = findDirectChild(e, BPMN_NS, "errorEventDefinition");
            if (errDef != null) errorCode = getAttr(errDef, "errorRef", getAttr(errDef, "errorCode", null));
        }
        if (messageRef != null && !messageRef.isBlank()) endType = EndEventType.MESSAGE;
        else if (errorCode != null && !errorCode.isBlank()) endType = EndEventType.ERROR;
        return new EndEvent(id, name, in, endType, messageRef, errorCode);
    }

    private FlowNode parseIntermediateCatchEvent(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "intermediateCatchEvent");
        String name = getAttr(e, "name", "");
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        String messageRef = getAttrNS(e, ENGINE_NS, "messageRef");
        if (messageRef == null && findDirectChild(e, BPMN_NS, "messageEventDefinition") != null) {
            Element msgDef = findDirectChild(e, BPMN_NS, "messageEventDefinition");
            if (msgDef != null) messageRef = getAttr(msgDef, "messageRef", null);
        }
        String timerDefinition = getAttrNS(e, ENGINE_NS, "timerDefinition");
        if (timerDefinition == null && findDirectChild(e, BPMN_NS, TIMER_EVENT_DEFINITION) != null) {
            Element timerDef = findDirectChild(e, BPMN_NS, TIMER_EVENT_DEFINITION);
            if (timerDef != null) {
                Element timeDuration = findDirectChild(timerDef, BPMN_NS, "timeDuration");
                if (timeDuration != null && timeDuration.getTextContent() != null) timerDefinition = timeDuration.getTextContent().trim();
            }
        }
        CatchEventType catchType = (messageRef != null && !messageRef.isBlank()) ? CatchEventType.MESSAGE : CatchEventType.TIMER;
        return new IntermediateCatchEvent(id, name, in, out, catchType, messageRef, timerDefinition);
    }

    private FlowNode parseIntermediateThrowEvent(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "intermediateThrowEvent");
        String name = getAttr(e, "name", "");
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        String messageRef = getAttrNS(e, ENGINE_NS, MESSAGE_REF);
        if (messageRef == null && findDirectChild(e, BPMN_NS, MESSAGE_EVENT_DEFINITION) != null) {
            Element msgDef = findDirectChild(e, BPMN_NS, MESSAGE_EVENT_DEFINITION);
            if (msgDef != null) messageRef = getAttr(msgDef, MESSAGE_REF, null);
        }
        String signalRef = getAttrNS(e, ENGINE_NS, "signalRef");
        if (signalRef == null && findDirectChild(e, BPMN_NS, "signalEventDefinition") != null) {
            Element sigDef = findDirectChild(e, BPMN_NS, "signalEventDefinition");
            if (sigDef != null) signalRef = getAttr(sigDef, "signalRef", null);
        }
        ThrowEventType throwType = (messageRef != null && !messageRef.isBlank()) ? ThrowEventType.MESSAGE : ThrowEventType.SIGNAL;
        return new IntermediateThrowEvent(id, name, in, out, throwType, messageRef, signalRef);
    }

    private FlowNode parseServiceTask(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "serviceTask");
        String name = getAttr(e, "name", "");
        RestTaskConfiguration restConfiguration = parseRestTaskConfiguration(e);
        BeanTaskConfiguration beanConfiguration = parseBeanTaskConfiguration(e);
        KafkaTaskConfiguration kafkaConfiguration = parseKafkaTaskConfiguration(e);
        ServiceTaskType taskType = restConfiguration != null ? ServiceTaskType.REST
                : (beanConfiguration != null ? ServiceTaskType.BEAN
                : (kafkaConfiguration != null ? ServiceTaskType.KAFKA : ServiceTaskType.WORKER));
        String implementation = getAttr(e, "implementation", switch (taskType) {
            case REST -> "rest";
            case BEAN -> "bean";
            case KAFKA -> "kafka";
            case WORKER -> "";
        });
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new ServiceTask(id, name, implementation, taskType, restConfiguration, beanConfiguration, kafkaConfiguration, in, out);
    }

    private KafkaTaskConfiguration parseKafkaTaskConfiguration(Element serviceTaskEl) {
        Element extensionElements = findDirectChild(serviceTaskEl, BPMN_NS, EXTENSION_ELEMENTS);
        if (extensionElements == null) {
            return null;
        }

        Element taskConfiguration = findDirectChild(extensionElements, ENGINE_NS, TASK_CONFIGURATION);
        if (taskConfiguration == null) {
            return null;
        }

        String type = getAttr(taskConfiguration, "type", WORKER);
        if (!"kafka".equalsIgnoreCase(type)) {
            return null;
        }

        return new KafkaTaskConfiguration(
                getAttr(taskConfiguration, "topic", ""),
                getAttr(taskConfiguration, "messageMapping", ""),
                getAttr(taskConfiguration, "keyMapping", ""),
                getAttr(taskConfiguration, RESULT_VARIABLE, "")
        );
    }

    private RestTaskConfiguration parseRestTaskConfiguration(Element serviceTaskEl) {
        Element extensionElements = findDirectChild(serviceTaskEl, BPMN_NS, EXTENSION_ELEMENTS);
        if (extensionElements == null) {
            return null;
        }

        Element taskConfiguration = findDirectChild(extensionElements, ENGINE_NS, TASK_CONFIGURATION);
        if (taskConfiguration == null) {
            return null;
        }

        String type = getAttr(taskConfiguration, "type", WORKER);
        if (!"rest".equalsIgnoreCase(type)) {
            return null;
        }

        return new RestTaskConfiguration(
                getAttr(taskConfiguration, "method", "GET"),
                getAttr(taskConfiguration, "url", ""),
                getAttr(taskConfiguration, "authenticationType", "none"),
                getAttr(taskConfiguration, "apiKeyLocation", "header"),
                getAttr(taskConfiguration, "apiKeyName", ""),
                getAttr(taskConfiguration, "apiKeyValue", ""),
                getAttr(taskConfiguration, "username", ""),
                getAttr(taskConfiguration, "password", ""),
                getAttr(taskConfiguration, "bearerToken", ""),
                getAttr(taskConfiguration, "headers", ""),
                getAttr(taskConfiguration, "queryParameters", ""),
                getAttr(taskConfiguration, "body", ""),
                getAttr(taskConfiguration, RESULT_VARIABLE, ""),
                parseInteger(getAttr(taskConfiguration, "timeoutSeconds", "20"), 20)
        );
    }

    private BeanTaskConfiguration parseBeanTaskConfiguration(Element serviceTaskEl) {
        Element extensionElements = findDirectChild(serviceTaskEl, BPMN_NS, EXTENSION_ELEMENTS);
        if (extensionElements == null) {
            return null;
        }

        Element taskConfiguration = findDirectChild(extensionElements, ENGINE_NS, TASK_CONFIGURATION);
        if (taskConfiguration == null) {
            return null;
        }

        String type = getAttr(taskConfiguration, "type", WORKER);
        if (!"bean".equalsIgnoreCase(type)) {
            return null;
        }

        return new BeanTaskConfiguration(
                getAttr(taskConfiguration, "beanName", ""),
                getAttr(taskConfiguration, "inputMapping", ""),
                getAttr(taskConfiguration, RESULT_VARIABLE, "")
        );
    }

    private static Integer parseInteger(String rawValue, Integer defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Element findDirectChild(Element parent, String namespace, String localName) {
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node child = childNodes.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            String childLocalName = element.getLocalName();
            String childNamespace = element.getNamespaceURI();
            if (localName.equals(childLocalName) && Objects.equals(namespace, childNamespace)) {
                return element;
            }
        }
        return null;
    }

    private FlowNode parseUserTask(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "userTask");
        String name = getAttr(e, "name", "");
        String assignee = getAssignee(e);
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new UserTask(id, name, assignee, in, out);
    }

    private FlowNode parseExclusiveGateway(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "exclusiveGateway");
        String name = getAttr(e, "name", "");
        String defaultFlow = getAttr(e, DEFAULT, null);
        if (defaultFlow != null && defaultFlow.isBlank()) defaultFlow = null;
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new ExclusiveGateway(id, name, defaultFlow, in, out);
    }

    private FlowNode parseParallelGateway(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "parallelGateway");
        String name = getAttr(e, "name", "");
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new ParallelGateway(id, name, in, out);
    }

    private FlowNode parseInclusiveGateway(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "inclusiveGateway");
        String name = getAttr(e, "name", "");
        String defaultFlow = getAttr(e, DEFAULT, null);
        if (defaultFlow != null && defaultFlow.isBlank()) defaultFlow = null;
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new InclusiveGateway(id, name, defaultFlow, in, out);
    }

    private FlowNode parseComplexGateway(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "complexGateway");
        String name = getAttr(e, "name", "");
        String defaultFlow = getAttr(e, DEFAULT, null);
        if (defaultFlow != null && defaultFlow.isBlank()) defaultFlow = null;
        String activationExpression = getAttrNS(e, ENGINE_NS, "activationExpression");
        if (activationExpression == null || activationExpression.isBlank()) {
            activationExpression = getAttr(e, "activationExpression", null);
        }
        String activationLanguage = getAttrNS(e, ENGINE_NS, "activationLanguage");
        if (activationLanguage == null || activationLanguage.isBlank()) {
            activationLanguage = getAttr(e, "activationLanguage", null);
        }
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new ComplexGateway(id, name, defaultFlow, activationExpression, activationLanguage, in, out);
    }

    private FlowNode parseEventBasedGateway(Element e, Map<String, List<String>> incoming, Map<String, List<String>> outgoing) throws BpmnParseException {
        String id = requireAttr(e, "id", "eventBasedGateway");
        String name = getAttr(e, "name", "");
        String defaultFlow = getAttr(e, DEFAULT, null);
        if (defaultFlow != null && defaultFlow.isBlank()) defaultFlow = null;
        List<String> in = incoming.getOrDefault(id, List.of());
        List<String> out = outgoing.getOrDefault(id, List.of());
        return new EventBasedGateway(id, name, defaultFlow, in, out);
    }

    private static String getAttrNS(Element el, String namespace, String localName) {
        String v = el.getAttributeNS(namespace, localName);
        if (v == null || v.isBlank()) return null;
        return v.trim();
    }

    /**
     * Serializes a ProcessDefinition back to BPMN 2.0 XML.
     *
     * @param definition the process definition to serialize
     * @return BPMN 2.0 XML string
     */
    public String serialize(ProcessDefinition definition) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" ");
        sb.append("xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" ");
        sb.append("xmlns:engine=\"").append(ENGINE_NS).append("\" ");
        sb.append("xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\">\n");
        sb.append("  <bpmn:process id=\"").append(escape(definition.id())).append("\" ");
        sb.append("name=\"").append(escape(definition.name())).append("\">\n");

        for (FlowNode node : definition.nodes().values()) {
            serializeNode(sb, node, definition.sequenceFlows());
        }

        for (SequenceFlow flow : definition.sequenceFlows().values()) {
            serializeSequenceFlow(sb, flow);
        }

        sb.append("  </bpmn:process>\n");
        sb.append("</bpmn:definitions>");
        return sb.toString();
    }

    /**
     * Serializes a ProcessDefinition to BPMN 2.0 XML including diagram interchange (BPMNDiagram with
     * BPMNShape and BPMNEdge) so viewers can render shapes and sequence flow arrows.
     */
    public String serializeWithDiagram(ProcessDefinition definition) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" ");
        sb.append("xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" ");
        sb.append("xmlns:engine=\"").append(ENGINE_NS).append("\" ");
        sb.append("xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" ");
        sb.append("xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\">\n");
        sb.append("  <bpmn:process id=\"").append(escape(definition.id())).append("\" ");
        sb.append("name=\"").append(escape(definition.name())).append("\">\n");

        for (FlowNode node : definition.nodes().values()) {
            serializeNode(sb, node, definition.sequenceFlows());
        }

        for (SequenceFlow flow : definition.sequenceFlows().values()) {
            serializeSequenceFlow(sb, flow);
        }

        sb.append("  </bpmn:process>\n");

        // Diagram interchange: simple horizontal layout so arrows (BPMNEdge) are visible in viewers
        List<String> nodeOrder = topologicalOrder(definition);
        Map<String, Bounds> boundsMap = new LinkedHashMap<>();
        int x = 152;
        int yBase = 82;
        for (String nodeId : nodeOrder) {
            FlowNode node = definition.nodes().get(nodeId);
            if (node == null) continue;
            int w = widthFor(node);
            int h = heightFor(node);
            int y = yBase - (h - 36) / 2;
            boundsMap.put(nodeId, new Bounds(x, y, w, h));
            x += w + 50;
        }

        sb.append("  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">\n");
        sb.append("    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"").append(escape(definition.id())).append("\">\n");

        for (String nodeId : nodeOrder) {
            FlowNode node = definition.nodes().get(nodeId);
            Bounds b = boundsMap.get(nodeId);
            if (node != null && b != null) {
                sb.append("      <bpmndi:BPMNShape id=\"Shape_").append(escape(nodeId)).append("\" bpmnElement=\"").append(escape(nodeId)).append("\">");
                sb.append("<dc:Bounds x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h).append("\"/></bpmndi:BPMNShape>\n");
            }
        }

        for (SequenceFlow flow : definition.sequenceFlows().values()) {
            Bounds src = boundsMap.get(flow.sourceRef());
            Bounds tgt = boundsMap.get(flow.targetRef());
            if (src != null && tgt != null) {
                int sx2 = src.x + src.w;
                int sy = src.y + src.h / 2;
                int tx = tgt.x;
                int ty = tgt.y + tgt.h / 2;
                sb.append("      <bpmndi:BPMNEdge id=\"Edge_").append(escape(flow.id())).append("\" bpmnElement=\"").append(escape(flow.id())).append("\">");
                sb.append("<di:waypoint x=\"").append(sx2).append("\" y=\"").append(sy).append("\"/>");
                sb.append("<di:waypoint x=\"").append(tx).append("\" y=\"").append(ty).append("\"/></bpmndi:BPMNEdge>\n");
            }
        }

        sb.append("    </bpmndi:BPMNPlane>\n");
        sb.append("  </bpmndi:BPMNDiagram>\n");
        sb.append("</bpmn:definitions>");
        return sb.toString();
    }

    private static List<String> topologicalOrder(ProcessDefinition definition) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(definition.startNodeId());
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (n == null || !visited.add(n)) continue;
            order.add(n);
            List<String> out = definition.sequenceFlows().values().stream()
                    .filter(f -> f.sourceRef().equals(n))
                    .map(SequenceFlow::targetRef)
                    .distinct()
                    .toList();
            queue.addAll(out);
        }
        for (String nodeId : definition.nodes().keySet()) {
            if (!visited.contains(nodeId)) order.add(nodeId);
        }
        return order;
    }

    private static int widthFor(FlowNode node) {
        return switch (node) {
            case StartEvent s -> 36;
            case EndEvent e -> 36;
            case IntermediateCatchEvent i1 -> 36;
            case IntermediateThrowEvent i2 -> 36;
            case ExclusiveGateway g1 -> 50;
            case ParallelGateway g2 -> 50;
            case InclusiveGateway g3 -> 50;
            case ComplexGateway g4 -> 50;
            case EventBasedGateway g5 -> 50;
            default -> 100;
        };
    }

    private static int heightFor(FlowNode node) {
        return switch (node) {
            case StartEvent s -> 36;
            case EndEvent e -> 36;
            case IntermediateCatchEvent i1 -> 36;
            case IntermediateThrowEvent i2 -> 36;
            case ExclusiveGateway g1 -> 50;
            case ParallelGateway g2 -> 50;
            case InclusiveGateway g3 -> 50;
            case ComplexGateway g4 -> 50;
            case EventBasedGateway g5 -> 50;
            default -> 80;
        };
    }

    private record Bounds(int x, int y, int w, int h) {}

    private void serializeNode(StringBuilder sb, FlowNode node, Map<String, SequenceFlow> sequenceFlows) {
        String id = escape(node.id());
        String name = escape(node.name());
        List<String> outgoingFlowIds = sequenceFlows.values().stream()
                .filter(f -> f.sourceRef().equals(node.id()))
                .map(SequenceFlow::id)
                .toList();
        List<String> incomingFlowIds = sequenceFlows.values().stream()
                .filter(f -> f.targetRef().equals(node.id()))
                .map(SequenceFlow::id)
                .toList();

        switch (node) {
            case StartEvent start -> {
                sb.append("    <bpmn:startEvent id=\"").append(id).append("\" name=\"").append(name).append("\"");
                if (start.messageRef() != null && !start.messageRef().isBlank())
                    sb.append(" engine:messageRef=\"").append(escape(start.messageRef())).append("\"");
                if (start.timerDefinition() != null && !start.timerDefinition().isBlank())
                    sb.append(" engine:timerDefinition=\"").append(escape(start.timerDefinition())).append("\"");
                sb.append(">\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:startEvent>\n");
            }
            case EndEvent end -> {
                sb.append("    <bpmn:endEvent id=\"").append(id).append("\" name=\"").append(name).append("\"");
                if (end.messageRef() != null && !end.messageRef().isBlank())
                    sb.append(" engine:messageRef=\"").append(escape(end.messageRef())).append("\"");
                if (end.errorCode() != null && !end.errorCode().isBlank())
                    sb.append(" engine:errorCode=\"").append(escape(end.errorCode())).append("\"");
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                sb.append("    </bpmn:endEvent>\n");
            }
            case ServiceTask s -> {
                sb.append("    <bpmn:serviceTask id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (s.implementation() != null && !s.implementation().isBlank()) {
                    sb.append("implementation=\"").append(escape(s.implementation())).append("\" ");
                }
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                if (s.taskType() == ServiceTaskType.REST && s.restConfiguration() != null) {
                    serializeRestTaskConfiguration(sb, s.restConfiguration());
                } else if (s.taskType() == ServiceTaskType.BEAN && s.beanConfiguration() != null) {
                    serializeBeanTaskConfiguration(sb, s.beanConfiguration());
                } else if (s.taskType() == ServiceTaskType.KAFKA && s.kafkaConfiguration() != null) {
                    serializeKafkaTaskConfiguration(sb, s.kafkaConfiguration());
                }
                sb.append("    </bpmn:serviceTask>\n");
            }
            case UserTask u -> {
                sb.append("    <bpmn:userTask id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (u.assignee() != null && !u.assignee().isBlank()) {
                    sb.append("camunda:assignee=\"").append(escape(u.assignee())).append("\" ");
                }
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:userTask>\n");
            }
            case ExclusiveGateway ex -> {
                sb.append("    <bpmn:exclusiveGateway id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (ex.defaultFlow() != null && !ex.defaultFlow().isBlank()) {
                    sb.append("default=\"").append(escape(ex.defaultFlow())).append("\" ");
                }
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:exclusiveGateway>\n");
            }
            case ParallelGateway ignored -> {
                sb.append("    <bpmn:parallelGateway id=\"").append(id).append("\" name=\"").append(name).append("\">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:parallelGateway>\n");
            }
            case InclusiveGateway inc -> {
                sb.append("    <bpmn:inclusiveGateway id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (inc.defaultFlow() != null && !inc.defaultFlow().isBlank()) {
                    sb.append("default=\"").append(escape(inc.defaultFlow())).append("\" ");
                }
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:inclusiveGateway>\n");
            }
            case ComplexGateway cg -> {
                sb.append("    <bpmn:complexGateway id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (cg.defaultFlow() != null && !cg.defaultFlow().isBlank()) sb.append("default=\"").append(escape(cg.defaultFlow())).append("\" ");
                if (cg.activationExpression() != null && !cg.activationExpression().isBlank()) {
                    sb.append("engine:activationExpression=\"").append(escape(cg.activationExpression())).append("\" ");
                    if (cg.activationLanguage() != null && !cg.activationLanguage().isBlank()) {
                        sb.append("engine:activationLanguage=\"").append(escape(cg.activationLanguage())).append("\" ");
                    }
                }
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:complexGateway>\n");
            }
            case EventBasedGateway ev -> {
                sb.append("    <bpmn:eventBasedGateway id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (ev.defaultFlow() != null && !ev.defaultFlow().isBlank()) sb.append("default=\"").append(escape(ev.defaultFlow())).append("\" ");
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:eventBasedGateway>\n");
            }
            case IntermediateCatchEvent ice -> {
                sb.append("    <bpmn:intermediateCatchEvent id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (ice.messageRef() != null && !ice.messageRef().isBlank()) sb.append("engine:messageRef=\"").append(escape(ice.messageRef())).append("\" ");
                if (ice.timerDefinition() != null && !ice.timerDefinition().isBlank()) sb.append("engine:timerDefinition=\"").append(escape(ice.timerDefinition())).append("\" ");
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:intermediateCatchEvent>\n");
            }
            case IntermediateThrowEvent ite -> {
                sb.append("    <bpmn:intermediateThrowEvent id=\"").append(id).append("\" name=\"").append(name).append("\" ");
                if (ite.messageRef() != null && !ite.messageRef().isBlank()) sb.append("engine:messageRef=\"").append(escape(ite.messageRef())).append("\" ");
                if (ite.signalRef() != null && !ite.signalRef().isBlank()) sb.append("engine:signalRef=\"").append(escape(ite.signalRef())).append("\" ");
                sb.append(">\n");
                for (String flowId : incomingFlowIds) sb.append("      <bpmn:incoming>").append(escape(flowId)).append("</bpmn:incoming>\n");
                for (String flowId : outgoingFlowIds) sb.append("      <bpmn:outgoing>").append(escape(flowId)).append("</bpmn:outgoing>\n");
                sb.append("    </bpmn:intermediateThrowEvent>\n");
            }
        }
    }

    private void serializeSequenceFlow(StringBuilder sb, SequenceFlow flow) {
        sb.append("    <bpmn:sequenceFlow id=\"").append(escape(flow.id())).append("\" ");
        sb.append("sourceRef=\"").append(escape(flow.sourceRef())).append("\" ");
        sb.append("targetRef=\"").append(escape(flow.targetRef())).append("\"");
        if (flow.conditionExpression() != null && !flow.conditionExpression().isBlank()) {
            sb.append(">\n      <bpmn:conditionExpression");
            if (flow.conditionExpressionLanguage() != null && !flow.conditionExpressionLanguage().isBlank()) {
                sb.append(" language=\"").append(escape(flow.conditionExpressionLanguage())).append("\"");
            }
            sb.append(">").append(escape(flow.conditionExpression())).append("</bpmn:conditionExpression>\n    </bpmn:sequenceFlow>\n");
        } else {
            sb.append("/>\n");
        }
    }

    private void serializeRestTaskConfiguration(StringBuilder sb, RestTaskConfiguration configuration) {
        sb.append("      <bpmn:extensionElements>\n");
        sb.append("        <engine:taskConfiguration type=\"rest\"");
        appendAttribute(sb, "method", configuration.method());
        appendAttribute(sb, "url", configuration.url());
        appendAttribute(sb, "authenticationType", configuration.authenticationType());
        appendAttribute(sb, "apiKeyLocation", configuration.apiKeyLocation());
        appendAttribute(sb, "apiKeyName", configuration.apiKeyName());
        appendAttribute(sb, "apiKeyValue", configuration.apiKeyValue());
        appendAttribute(sb, "username", configuration.username());
        appendAttribute(sb, "password", configuration.password());
        appendAttribute(sb, "bearerToken", configuration.bearerToken());
        appendAttribute(sb, "headers", configuration.headers());
        appendAttribute(sb, "queryParameters", configuration.queryParameters());
        appendAttribute(sb, "body", configuration.body());
        appendAttribute(sb, RESULT_VARIABLE, configuration.resultVariable());
        if (configuration.timeoutSeconds() != null) {
            appendAttribute(sb, "timeoutSeconds", String.valueOf(configuration.timeoutSeconds()));
        }
        sb.append("/>\n");
        sb.append("      </bpmn:extensionElements>\n");
    }

    private void serializeBeanTaskConfiguration(StringBuilder sb, BeanTaskConfiguration configuration) {
        sb.append("      <bpmn:extensionElements>\n");
        sb.append("        <engine:taskConfiguration type=\"bean\"");
        appendAttribute(sb, "beanName", configuration.beanName());
        appendAttribute(sb, "inputMapping", configuration.inputMapping());
        appendAttribute(sb, RESULT_VARIABLE, configuration.resultVariable());
        sb.append("/>\n");
        sb.append("      </bpmn:extensionElements>\n");
    }

    private void serializeKafkaTaskConfiguration(StringBuilder sb, KafkaTaskConfiguration configuration) {
        sb.append("      <bpmn:extensionElements>\n");
        sb.append("        <engine:taskConfiguration type=\"kafka\"");
        appendAttribute(sb, "topic", configuration.topic());
        appendAttribute(sb, "messageMapping", configuration.messageMapping());
        appendAttribute(sb, "keyMapping", configuration.keyMapping());
        appendAttribute(sb, RESULT_VARIABLE, configuration.resultVariable());
        sb.append("/>\n");
        sb.append("      </bpmn:extensionElements>\n");
    }

    private void appendAttribute(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(" ").append(name).append("=\"").append(escape(value)).append("\"");
        }
    }

    private record ExpressionDefinition(String expression, String language) {
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
