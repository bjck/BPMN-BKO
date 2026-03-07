import { API, fetchJson } from '../core/api.js';
import { getBpmnModelerConstructor } from '../core/bpmn-bundles.js';
import { escapeHtml } from '../core/ui.js';
import { ENGINE_MODDLE_DESCRIPTOR } from './engine-moddle.js';

const ENGINE_TASK_CONFIG_TYPE = 'engine:TaskConfiguration';
const BPMN_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const BPMNDI_NS = 'http://www.omg.org/spec/BPMN/20100524/DI';
const DC_NS = 'http://www.omg.org/spec/DD/20100524/DC';
const ENGINE_NS = 'https://bko.dev/schema/bpmn-engine/1.0';
const CAMUNDA_NS = 'http://camunda.org/schema/1.0/bpmn';
const AI_APPEND_GAP_X = 220;
const AI_APPEND_GAP_Y = 120;
const DEFAULT_REST_TASK_CONFIG = {
  type: 'rest',
  method: 'GET',
  url: '',
  authenticationType: 'none',
  apiKeyLocation: 'header',
  apiKeyName: '',
  apiKeyValue: '',
  username: '',
  password: '',
  bearerToken: '',
  headers: '',
  queryParameters: '',
  body: '',
  resultVariable: '',
  timeoutSeconds: 20,
};

const DEFAULT_BEAN_TASK_CONFIG = {
  type: 'bean',
  beanName: '',
  inputMapping: '',
  resultVariable: '',
};

const DEFAULT_KAFKA_TASK_CONFIG = {
  type: 'kafka',
  topic: '',
  messageMapping: '',
  keyMapping: '',
  resultVariable: '',
};

const MINIMAL_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
  <bpmn:process id="Process_1" name="New Process" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="Start">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:endEvent id="EndEvent_1" name="End">
      <bpmn:incoming>Flow_1</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_1" id="BPMNPlane_1">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="BPMNShape_StartEvent_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape bpmnElement="EndEvent_1" id="BPMNShape_EndEvent_1"><dc:Bounds x="248" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="BPMNEdge_Flow_1"><di:waypoint x="188" y="100"/><di:waypoint x="248" y="100"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

let camundaDescriptorPromise = null;

async function loadCamundaDescriptor() {
  if (!camundaDescriptorPromise) {
    camundaDescriptorPromise = fetch('https://unpkg.com/camunda-bpmn-moddle@4.4.0/resources/camunda.json')
      .then((response) => response.ok ? response.json() : {})
      .catch(() => ({}));
  }

  return camundaDescriptorPromise;
}

function getEngineTaskConfiguration(businessObject) {
  return businessObject.extensionElements?.values?.find((value) => value.$type === ENGINE_TASK_CONFIG_TYPE) || null;
}

function getServiceTaskMode(businessObject) {
  const taskConfiguration = getEngineTaskConfiguration(businessObject);
  if (taskConfiguration?.type === 'bean' || businessObject.implementation === 'bean') {
    return 'bean';
  }
  if (taskConfiguration?.type === 'rest' || businessObject.implementation === 'rest') {
    return 'rest';
  }
  if (taskConfiguration?.type === 'kafka' || businessObject.implementation === 'kafka') {
    return 'kafka';
  }
  return 'worker';
}

function getRestTaskConfig(businessObject) {
  const taskConfiguration = getEngineTaskConfiguration(businessObject);
  return { ...DEFAULT_REST_TASK_CONFIG, ...(taskConfiguration || {}) };
}

function getBeanTaskConfig(businessObject) {
  const taskConfiguration = getEngineTaskConfiguration(businessObject);
  return { ...DEFAULT_BEAN_TASK_CONFIG, ...(taskConfiguration || {}) };
}

function getKafkaTaskConfig(businessObject) {
  const taskConfiguration = getEngineTaskConfiguration(businessObject);
  return { ...DEFAULT_KAFKA_TASK_CONFIG, ...(taskConfiguration || {}) };
}

function getConditionLanguage(businessObject) {
  const language = businessObject.conditionExpression?.language || '';
  if (language) {
    return language;
  }
  const body = businessObject.conditionExpression?.body || '';
  return body.trim().startsWith('=') ? 'feel' : 'spel';
}

export class BpmnEditorApp {
  constructor({ canvasElement, propertyPlaceholder, propertyForm, showToast }) {
    this.canvasElement = canvasElement;
    this.propertyPlaceholder = propertyPlaceholder;
    this.propertyForm = propertyForm;
    this.showToast = showToast;
    this.modeler = null;
    this.serviceTaskLogics = [];
    /** Element ID -> engine config (messageRef, timerDefinition, errorCode, signalRef, defaultFlow, activationExpression, activationLanguage) for events/gateways */
    this.engineConfigByElementId = {};
  }

  async init() {
    if (!this.canvasElement) {
      return;
    }

    const Modeler = getBpmnModelerConstructor();
    if (!Modeler) {
      this.canvasElement.innerHTML = '<span class="error">The BPMN modeler bundle did not load.</span>';
      return;
    }

    this.canvasElement.innerHTML = '';

    const camundaDescriptor = await loadCamundaDescriptor();
    await this.loadServiceTaskLogics();
    this.modeler = new Modeler({
      container: this.canvasElement,
      keyboard: { bindTo: document },
      moddleExtensions: {
        camunda: camundaDescriptor,
        engine: ENGINE_MODDLE_DESCRIPTOR,
      },
    });

    const eventBus = this.modeler.get('eventBus');
    eventBus.on('selection.changed', ({ newSelection }) => {
      this.renderProperties(newSelection.length === 1 ? newSelection[0] : null);
    });
    eventBus.on('element.click', ({ element }) => {
      this.renderProperties(element);
    });
    eventBus.on('commandStack.changed', () => {
      this.renderProperties(this.getSelectedElement());
    });

    try {
      await this.loadXml(MINIMAL_BPMN);
    } catch (error) {
      this.canvasElement.innerHTML = `<span class="error">Failed to initialize editor: ${escapeHtml(error.message)}</span>`;
    }
  }

  async loadServiceTaskLogics() {
    try {
      const response = await fetchJson(`${API}/service-task-logics`);
      this.serviceTaskLogics = response.serviceTaskLogics || [];
    } catch (_) {
      this.serviceTaskLogics = [];
    }
  }

  destroy() {
    if (this.modeler) {
      this.modeler.destroy();
      this.modeler = null;
    }

    if (this.canvasElement) {
      this.canvasElement.innerHTML = '';
    }
  }

  getSelectedElement() {
    if (!this.modeler) {
      return null;
    }

    const selection = this.modeler.get('selection').get();
    return selection.length === 1 ? selection[0] : null;
  }

  async loadXml(xml) {
    if (!this.modeler) {
      return;
    }

    this.parseEngineConfigFromXml(xml);
    await this.modeler.importXML(xml);
    this.modeler.get('canvas').zoom('fit-viewport');
    this.renderProperties(null);
  }

  parseEngineConfigFromXml(xml) {
    this.engineConfigByElementId = {};
    const parser = new DOMParser();
    const doc = parser.parseFromString(xml, 'application/xml');
    const getAttr = (el, localName) => el.getAttributeNS(ENGINE_NS, localName) || el.getAttribute(localName) || '';

    [...doc.getElementsByTagNameNS(BPMN_NS, 'startEvent')].forEach((el) => {
      const id = el.getAttribute('id');
      if (id) {
        this.engineConfigByElementId[id] = {
          ...this.engineConfigByElementId[id],
          messageRef: getAttr(el, 'messageRef') || undefined,
          timerDefinition: getAttr(el, 'timerDefinition') || undefined,
        };
      }
    });
    [...doc.getElementsByTagNameNS(BPMN_NS, 'endEvent')].forEach((el) => {
      const id = el.getAttribute('id');
      if (id) {
        this.engineConfigByElementId[id] = {
          ...this.engineConfigByElementId[id],
          messageRef: getAttr(el, 'messageRef') || undefined,
          errorCode: getAttr(el, 'errorCode') || undefined,
        };
      }
    });
    [...doc.getElementsByTagNameNS(BPMN_NS, 'intermediateCatchEvent')].forEach((el) => {
      const id = el.getAttribute('id');
      if (id) {
        this.engineConfigByElementId[id] = {
          ...this.engineConfigByElementId[id],
          messageRef: getAttr(el, 'messageRef') || undefined,
          timerDefinition: getAttr(el, 'timerDefinition') || undefined,
        };
      }
    });
    [...doc.getElementsByTagNameNS(BPMN_NS, 'intermediateThrowEvent')].forEach((el) => {
      const id = el.getAttribute('id');
      if (id) {
        this.engineConfigByElementId[id] = {
          ...this.engineConfigByElementId[id],
          messageRef: getAttr(el, 'messageRef') || undefined,
          signalRef: getAttr(el, 'signalRef') || undefined,
        };
      }
    });
    ['exclusiveGateway', 'inclusiveGateway', 'eventBasedGateway'].forEach((tag) => {
      [...doc.getElementsByTagNameNS(BPMN_NS, tag)].forEach((el) => {
        const id = el.getAttribute('id');
        if (id) {
          this.engineConfigByElementId[id] = {
            ...this.engineConfigByElementId[id],
            defaultFlow: el.getAttribute('default') || undefined,
          };
        }
      });
    });
    [...doc.getElementsByTagNameNS(BPMN_NS, 'complexGateway')].forEach((el) => {
      const id = el.getAttribute('id');
      if (id) {
        this.engineConfigByElementId[id] = {
          ...this.engineConfigByElementId[id],
          defaultFlow: el.getAttribute('default') || undefined,
          activationExpression: getAttr(el, 'activationExpression') || undefined,
          activationLanguage: getAttr(el, 'activationLanguage') || undefined,
        };
      }
    });
  }

  injectEngineConfigIntoXml(xmlString) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(xmlString, 'application/xml');
    const defs = doc.documentElement;
    if (!defs.getAttributeNS('http://www.w3.org/2000/xmlns/', 'engine')) {
      defs.setAttributeNS('http://www.w3.org/2000/xmlns/', 'xmlns:engine', ENGINE_NS);
    }

    const setAttr = (el, localName, value) => {
      if (value != null && value !== '') {
        el.setAttributeNS(ENGINE_NS, localName, value);
      }
    };

    const findById = (root, id) => {
      const byId = doc.getElementById(id);
      if (byId) return byId;
      const all = root.getElementsByTagName('*');
      for (let i = 0; i < all.length; i++) {
        if (all[i].getAttribute('id') === id) return all[i];
      }
      return null;
    };

    Object.entries(this.engineConfigByElementId).forEach(([elementId, config]) => {
      if (!config) return;
      const el = findById(doc, elementId);
      if (!el) return;
      const local = el.localName;
      if (local === 'startEvent') {
        setAttr(el, 'messageRef', config.messageRef);
        setAttr(el, 'timerDefinition', config.timerDefinition);
      } else if (local === 'endEvent') {
        setAttr(el, 'messageRef', config.messageRef);
        setAttr(el, 'errorCode', config.errorCode);
      } else if (local === 'intermediateCatchEvent') {
        setAttr(el, 'messageRef', config.messageRef);
        setAttr(el, 'timerDefinition', config.timerDefinition);
      } else if (local === 'intermediateThrowEvent') {
        setAttr(el, 'messageRef', config.messageRef);
        setAttr(el, 'signalRef', config.signalRef);
      } else if (local === 'exclusiveGateway' || local === 'inclusiveGateway' || local === 'eventBasedGateway') {
        if (config.defaultFlow) el.setAttribute('default', config.defaultFlow);
      } else if (local === 'complexGateway') {
        if (config.defaultFlow) el.setAttribute('default', config.defaultFlow);
        setAttr(el, 'activationExpression', config.activationExpression);
        setAttr(el, 'activationLanguage', config.activationLanguage);
      }
    });

    return new XMLSerializer().serializeToString(doc);
  }

  async createNewDiagram() {
    this.engineConfigByElementId = {};
    await this.loadXml(MINIMAL_BPMN);
    this.showToast('New diagram loaded', 'success');
  }

  async copyXml() {
    if (!this.modeler) {
      return;
    }

    const xml = await this.getCurrentXml();
    await navigator.clipboard.writeText(xml);
    this.showToast('XML copied to clipboard', 'success');
  }

  async deployCurrentDiagram() {
    if (!this.modeler) {
      return null;
    }

    const xml = await this.getCurrentXml();
    const response = await fetchJson(`${API}/processes`, {
      method: 'POST',
      body: JSON.stringify({ bpmnXml: xml }),
    });

    return response.processDefinitionId;
  }

  async getCurrentXml() {
    if (!this.modeler) {
      return '';
    }

    const { xml } = await this.modeler.saveXML({ format: true });
    return this.injectEngineConfigIntoXml(xml);
  }

  async getContextSnapshot() {
    const selectedElement = this.getSelectedElement();
    const businessObject = selectedElement?.businessObject;
    const properties = {};

    this.propertyForm?.querySelectorAll('input, select, textarea').forEach((field) => {
      if (field.id) {
        properties[field.id.replace(/^prop-/, '')] = field.value;
      }
    });

    let currentXml = '';
    try {
      currentXml = await this.getCurrentXml();
    } catch (_) {
      currentXml = '';
    }

    return {
      serviceTaskLogics: this.serviceTaskLogics.map((logic) => logic.displayName || logic.beanName),
      currentXml: currentXml.length > 3200 ? `${currentXml.slice(0, 3200)}\n... [truncated]` : currentXml,
      selectedElement: selectedElement
        ? {
            id: selectedElement.id,
            type: businessObject?.$type || selectedElement.type,
            name: businessObject?.name || '',
            properties,
          }
        : null,
    };
  }

  async applyAiGeneratedDiagram(diagramUpdate) {
    if (!diagramUpdate?.bpmnXml?.trim()) {
      throw new Error('AI response did not include BPMN XML.');
    }

    const requestedMode = (diagramUpdate.mode || 'append').trim().toLowerCase();
    if (requestedMode === 'replace' || (this.isMinimalDiagram() && requestedMode !== 'anchor')) {
      await this.loadXml(diagramUpdate.bpmnXml);
      const message = diagramUpdate.summary || 'AI diagram loaded into the editor.';
      this.showToast(message, 'success');
      return { applied: true, mode: 'replace', message };
    }

    const applied = await this.appendAiGeneratedFlow(diagramUpdate);
    const message = diagramUpdate.summary
      || (applied.mode === 'anchor'
        ? 'AI-generated BPMN was connected to the selected element.'
        : 'AI-generated BPMN was added as a separate flow.');
    this.showToast(message, 'success');
    return { applied: true, mode: applied.mode, message };
  }

  isMinimalDiagram() {
    if (!this.modeler) {
      return true;
    }

    const elementRegistry = this.modeler.get('elementRegistry');
    const flowNodes = elementRegistry.getAll().filter((element) => {
      if (!element || element.labelTarget || element.waypoints) {
        return false;
      }
      const type = element.businessObject?.$type || element.type;
      return type && type !== 'bpmn:Process';
    });
    return flowNodes.length <= 2;
  }

  async appendAiGeneratedFlow(diagramUpdate) {
    if (!this.modeler) {
      throw new Error('The BPMN editor is not ready yet.');
    }

    const spec = this.parseGeneratedDiagram(diagramUpdate.bpmnXml);
    if (!spec.nodes.length) {
      throw new Error('AI-generated BPMN contains no flow nodes to insert.');
    }

    const requestedAnchorId = diagramUpdate.anchorElementId?.trim() || '';
    const elementRegistry = this.modeler.get('elementRegistry');
    const selectedElement = requestedAnchorId ? elementRegistry.get(requestedAnchorId) : this.getSelectedElement();
    const shouldAnchor = (diagramUpdate.mode || '').trim().toLowerCase() === 'anchor'
      && selectedElement
      && this.canAnchorToElement(selectedElement)
      && spec.anchorStartTargetId;

    return this.insertGeneratedSpec(spec, {
      anchorElement: shouldAnchor ? selectedElement : null,
      diagramUpdate,
    });
  }

  canAnchorToElement(element) {
    const type = element?.businessObject?.$type || element?.type || '';
    return Boolean(type)
      && !type.includes('EndEvent')
      && !type.includes('SequenceFlow')
      && !type.includes('Participant');
  }

  insertGeneratedSpec(spec, { anchorElement, diagramUpdate }) {
    const modeling = this.modeler.get('modeling');
    const canvas = this.modeler.get('canvas');
    const rootElement = canvas.getRootElement();
    const moddle = this.modeler.get('moddle');
    const nodeIdsToCreate = new Set(spec.nodes.map((node) => node.id));
    const skippedFlowIds = new Set();
    let appliedMode = 'append';

    if (anchorElement && spec.anchorStartNodeId && spec.anchorStartTargetId) {
      nodeIdsToCreate.delete(spec.anchorStartNodeId);
      spec.flows
        .filter((flow) => flow.sourceRef === spec.anchorStartNodeId)
        .forEach((flow) => skippedFlowIds.add(flow.id));
      appliedMode = 'anchor';
    }

    const generatedPositions = this.prepareGeneratedLayout(spec, [...nodeIdsToCreate]);
    const { offsetX, offsetY } = anchorElement
      ? this.getAnchorOffsets(anchorElement, spec.anchorStartTargetId, generatedPositions)
      : this.getAppendOffsets();

    const createdNodes = new Map();
    const nodeIdMap = new Map();
    spec.nodes
      .filter((node) => nodeIdsToCreate.has(node.id))
      .sort((left, right) => {
        const leftPos = generatedPositions.get(left.id) || { x: 0, y: 0 };
        const rightPos = generatedPositions.get(right.id) || { x: 0, y: 0 };
        return leftPos.x - rightPos.x || leftPos.y - rightPos.y;
      })
      .forEach((node) => {
        const normalized = generatedPositions.get(node.id) || { x: 120, y: 120 };
        const created = this.createGeneratedNode(node, {
          x: offsetX + normalized.x,
          y: offsetY + normalized.y,
          parent: rootElement,
        });
        createdNodes.set(node.id, created);
        nodeIdMap.set(node.id, created.id);
      });

    const createdFlows = new Map();
    const flowIdMap = new Map();

    if (anchorElement && spec.anchorStartTargetId) {
      const anchorTarget = createdNodes.get(spec.anchorStartTargetId);
      if (anchorTarget) {
        const anchorFlow = modeling.connect(anchorElement, anchorTarget, { type: 'bpmn:SequenceFlow' });
        const flowId = this.ensureUniqueId(spec.anchorFlowId || `Flow_${anchorElement.id}_to_${anchorTarget.id}`);
        modeling.updateProperties(anchorFlow, { id: flowId });
        createdFlows.set(spec.anchorFlowId || flowId, anchorFlow);
        flowIdMap.set(spec.anchorFlowId || flowId, flowId);
      }
    }

    spec.flows
      .filter((flow) => !skippedFlowIds.has(flow.id))
      .forEach((flow) => {
        const source = createdNodes.get(flow.sourceRef);
        const target = createdNodes.get(flow.targetRef);
        if (!source || !target) {
          return;
        }

        const connection = modeling.connect(source, target, { type: 'bpmn:SequenceFlow' });
        const connectionId = this.ensureUniqueId(flow.id || `Flow_${source.id}_to_${target.id}`);
        const properties = {
          id: connectionId,
          name: flow.name || undefined,
        };
        if (flow.conditionExpression) {
          properties.conditionExpression = moddle.create('bpmn:FormalExpression', {
            body: flow.conditionExpression,
            language: flow.conditionLanguage || 'feel',
          });
        }
        modeling.updateProperties(connection, properties);
        createdFlows.set(flow.id || connectionId, connection);
        flowIdMap.set(flow.id || connectionId, connectionId);
      });

    spec.nodes.forEach((node) => {
      if (!node.defaultFlowId) {
        return;
      }
      const createdNode = createdNodes.get(node.id);
      const defaultFlowId = flowIdMap.get(node.defaultFlowId);
      const defaultFlow = defaultFlowId ? elementRegistry.get(defaultFlowId) : null;
      if (createdNode && defaultFlow) {
        modeling.updateProperties(createdNode, { default: defaultFlow });
      }
    });

    canvas.zoom('fit-viewport');
    this.renderProperties(anchorElement ? createdNodes.get(spec.anchorStartTargetId) || anchorElement : null);

    return {
      applied: true,
      mode: appliedMode,
      message: diagramUpdate.summary || null,
    };
  }

  createGeneratedNode(node, { x, y, parent }) {
    const elementFactory = this.modeler.get('elementFactory');
    const modeling = this.modeler.get('modeling');
    const moddle = this.modeler.get('moddle');
    const type = node.type || 'bpmn:Task';
    const nodeId = this.ensureUniqueId(node.id || type.replace(':', '_'));
    const businessObject = moddle.create(type, {
      id: nodeId,
      name: node.name || undefined,
    });
    const shape = elementFactory.createShape({ type, businessObject });
    const created = modeling.createShape(shape, { x, y }, parent);

    if (type === 'bpmn:ServiceTask') {
      modeling.updateProperties(created, {
        name: node.name || undefined,
        implementation: node.implementation || undefined,
      });
      if (node.taskConfiguration) {
        this.upsertEngineTaskConfiguration(created, node.taskConfiguration);
      }
    } else if (type === 'bpmn:UserTask') {
      modeling.updateProperties(created, {
        name: node.name || undefined,
        assignee: node.assignee || undefined,
      });
    } else {
      modeling.updateProperties(created, { name: node.name || undefined });
    }

    return created;
  }

  prepareGeneratedLayout(spec, includedNodeIds) {
    const included = spec.nodes.filter((node) => includedNodeIds.includes(node.id));
    const bounded = included.filter((node) => node.bounds);
    const minX = bounded.length ? Math.min(...bounded.map((node) => node.bounds.x)) : 0;
    const minY = bounded.length ? Math.min(...bounded.map((node) => node.bounds.y)) : 0;
    const layout = new Map();

    included
      .slice()
      .sort((left, right) => {
        const leftX = left.bounds?.x ?? Number.MAX_SAFE_INTEGER;
        const rightX = right.bounds?.x ?? Number.MAX_SAFE_INTEGER;
        const leftY = left.bounds?.y ?? Number.MAX_SAFE_INTEGER;
        const rightY = right.bounds?.y ?? Number.MAX_SAFE_INTEGER;
        return leftX - rightX || leftY - rightY;
      })
      .forEach((node, index) => {
        if (node.bounds) {
          layout.set(node.id, {
            x: node.bounds.x - minX + node.bounds.width / 2,
            y: node.bounds.y - minY + node.bounds.height / 2,
          });
          return;
        }

        layout.set(node.id, {
          x: 120 + (index * 180),
          y: 120,
        });
      });

    return layout;
  }

  getAnchorOffsets(anchorElement, anchorTargetId, layout) {
    const anchorPosition = layout.get(anchorTargetId) || { x: 120, y: 120 };
    const desiredX = anchorElement.x + anchorElement.width + 180;
    const desiredY = anchorElement.y + (anchorElement.height / 2);
    return {
      offsetX: desiredX - anchorPosition.x,
      offsetY: desiredY - anchorPosition.y,
    };
  }

  getAppendOffsets() {
    const elementRegistry = this.modeler.get('elementRegistry');
    const flowNodes = elementRegistry.getAll().filter((element) => {
      if (!element || element.labelTarget || element.waypoints) {
        return false;
      }
      return (element.businessObject?.$type || element.type) !== 'bpmn:Process';
    });

    const maxX = flowNodes.length
      ? Math.max(...flowNodes.map((element) => element.x + element.width))
      : 0;
    const minY = flowNodes.length
      ? Math.min(...flowNodes.map((element) => element.y))
      : 0;

    return {
      offsetX: maxX + AI_APPEND_GAP_X,
      offsetY: Math.max(minY + 20, AI_APPEND_GAP_Y),
    };
  }

  parseGeneratedDiagram(xml) {
    const parser = new DOMParser();
    const document = parser.parseFromString(xml, 'application/xml');
    const parserError = document.querySelector('parsererror');
    if (parserError) {
      throw new Error('AI-generated BPMN XML is invalid.');
    }

    const process = document.getElementsByTagNameNS(BPMN_NS, 'process')[0];
    if (!process) {
      throw new Error('AI-generated BPMN XML does not contain a process.');
    }

    const boundsByElementId = this.collectBounds(document);
    const nodes = [
      ...this.collectNodes(process, 'startEvent', 'bpmn:StartEvent', boundsByElementId),
      ...this.collectNodes(process, 'task', 'bpmn:Task', boundsByElementId),
      ...this.collectNodes(process, 'serviceTask', 'bpmn:ServiceTask', boundsByElementId),
      ...this.collectNodes(process, 'userTask', 'bpmn:UserTask', boundsByElementId),
      ...this.collectNodes(process, 'exclusiveGateway', 'bpmn:ExclusiveGateway', boundsByElementId),
      ...this.collectNodes(process, 'parallelGateway', 'bpmn:ParallelGateway', boundsByElementId),
      ...this.collectNodes(process, 'inclusiveGateway', 'bpmn:InclusiveGateway', boundsByElementId),
      ...this.collectNodes(process, 'complexGateway', 'bpmn:ComplexGateway', boundsByElementId),
      ...this.collectNodes(process, 'eventBasedGateway', 'bpmn:EventBasedGateway', boundsByElementId),
      ...this.collectNodes(process, 'intermediateCatchEvent', 'bpmn:IntermediateCatchEvent', boundsByElementId),
      ...this.collectNodes(process, 'intermediateThrowEvent', 'bpmn:IntermediateThrowEvent', boundsByElementId),
      ...this.collectNodes(process, 'endEvent', 'bpmn:EndEvent', boundsByElementId),
    ];
    const flows = this.collectFlows(process);
    const startNodes = nodes.filter((node) => node.type === 'bpmn:StartEvent');
    let anchorStartNodeId = null;
    let anchorStartTargetId = null;
    let anchorFlowId = null;

    if (startNodes.length === 1) {
      const startNode = startNodes[0];
      const outgoing = flows.filter((flow) => flow.sourceRef === startNode.id);
      if (outgoing.length === 1) {
        anchorStartNodeId = startNode.id;
        anchorStartTargetId = outgoing[0].targetRef;
        anchorFlowId = outgoing[0].id;
      }
    }

    return {
      processId: process.getAttribute('id') || '',
      processName: process.getAttribute('name') || '',
      nodes,
      flows,
      anchorStartNodeId,
      anchorStartTargetId,
      anchorFlowId,
    };
  }

  collectBounds(document) {
    const result = new Map();
    [...document.getElementsByTagNameNS(BPMNDI_NS, 'BPMNShape')].forEach((shape) => {
      const elementId = shape.getAttribute('bpmnElement');
      const bounds = shape.getElementsByTagNameNS(DC_NS, 'Bounds')[0];
      if (!elementId || !bounds) {
        return;
      }

      result.set(elementId, {
        x: Number(bounds.getAttribute('x') || 0),
        y: Number(bounds.getAttribute('y') || 0),
        width: Number(bounds.getAttribute('width') || 100),
        height: Number(bounds.getAttribute('height') || 80),
      });
    });
    return result;
  }

  collectNodes(process, localName, type, boundsByElementId) {
    return [...process.getElementsByTagNameNS(BPMN_NS, localName)]
      .filter((element) => element.parentNode === process)
      .map((element) => ({
        id: element.getAttribute('id') || `${localName}_${Math.random().toString(36).slice(2, 8)}`,
        type,
        name: element.getAttribute('name') || '',
        implementation: element.getAttribute('implementation') || '',
        assignee: element.getAttributeNS(CAMUNDA_NS, 'assignee') || element.getAttribute('assignee') || '',
        defaultFlowId: element.getAttribute('default') || '',
        taskConfiguration: type === 'bpmn:ServiceTask' ? this.readTaskConfiguration(element) : null,
        bounds: boundsByElementId.get(element.getAttribute('id')) || null,
      }));
  }

  collectFlows(process) {
    return [...process.getElementsByTagNameNS(BPMN_NS, 'sequenceFlow')]
      .filter((element) => element.parentNode === process)
      .map((element) => {
        const conditionExpression = element.getElementsByTagNameNS(BPMN_NS, 'conditionExpression')[0];
        return {
          id: element.getAttribute('id') || '',
          name: element.getAttribute('name') || '',
          sourceRef: element.getAttribute('sourceRef') || '',
          targetRef: element.getAttribute('targetRef') || '',
          conditionExpression: conditionExpression?.textContent?.trim() || '',
          conditionLanguage: conditionExpression?.getAttribute('language') || '',
        };
      });
  }

  readTaskConfiguration(serviceTaskElement) {
    const extensionElements = [...serviceTaskElement.getElementsByTagNameNS(BPMN_NS, 'extensionElements')]
      .find((element) => element.parentNode === serviceTaskElement);
    const taskConfiguration = extensionElements
      ? [...extensionElements.getElementsByTagNameNS(ENGINE_NS, 'taskConfiguration')][0]
      : null;
    if (!taskConfiguration) {
      return null;
    }

    const config = {
      type: taskConfiguration.getAttribute('type') || '',
    };
    [...taskConfiguration.attributes].forEach((attribute) => {
      if (attribute.name === 'type') {
        return;
      }
      config[attribute.name] = attribute.value;
    });
    if (config.timeoutSeconds != null) {
      config.timeoutSeconds = parseInt(config.timeoutSeconds, 10) || 20;
    }
    return config;
  }

  ensureUniqueId(proposedId) {
    const elementRegistry = this.modeler.get('elementRegistry');
    const base = (proposedId || 'Element').trim().replace(/\s+/g, '_');
    if (!elementRegistry.get(base)) {
      return base;
    }

    let index = 2;
    while (elementRegistry.get(`${base}_${index}`)) {
      index += 1;
    }
    return `${base}_${index}`;
  }

  renderProperties(element) {
    if (!this.propertyPlaceholder || !this.propertyForm) {
      return;
    }

    if (!element) {
      this.propertyPlaceholder.classList.remove('hidden');
      this.propertyForm.classList.add('hidden');
      this.propertyForm.innerHTML = '';
      delete this.propertyForm.dataset.elementId;
      return;
    }

    const businessObject = element.businessObject;
    const type = businessObject?.$type || element.type;
    const isProcess = type === 'bpmn:Process';
    const isFlow = type === 'bpmn:SequenceFlow';
    const isGenericTask = type === 'bpmn:Task';
    const isServiceTask = type === 'bpmn:ServiceTask';
    const isUserTask = type === 'bpmn:UserTask';
    const isTaskLike = isGenericTask || isServiceTask || isUserTask;
    const isStartEvent = type === 'bpmn:StartEvent';
    const isEndEvent = type === 'bpmn:EndEvent';
    const isIntermediateCatchEvent = type === 'bpmn:IntermediateCatchEvent';
    const isIntermediateThrowEvent = type === 'bpmn:IntermediateThrowEvent';
    const isExclusiveGateway = type === 'bpmn:ExclusiveGateway';
    const isInclusiveGateway = type === 'bpmn:InclusiveGateway';
    const isParallelGateway = type === 'bpmn:ParallelGateway';
    const isComplexGateway = type === 'bpmn:ComplexGateway';
    const isEventBasedGateway = type === 'bpmn:EventBasedGateway';
    const engineCfg = this.engineConfigByElementId[element.id] || {};

    this.propertyPlaceholder.classList.add('hidden');
    this.propertyForm.classList.remove('hidden');
    this.propertyForm.dataset.elementId = element.id;

    let html = '';
    if (isProcess) {
      html = `
        <div class="form-row"><label>Process ID</label><input type="text" id="prop-id" value="${escapeHtml(businessObject.id || '')}" /></div>
        <div class="form-row"><label>Process name</label><input type="text" id="prop-name" value="${escapeHtml(businessObject.name || '')}" /></div>
      `;
    } else if (isFlow) {
      const conditionValue = businessObject.conditionExpression?.body || '';
      const conditionLanguage = getConditionLanguage(businessObject);
      html = `
        <div class="form-row"><label>Flow ID</label><input type="text" id="prop-id" value="${escapeHtml(businessObject.id || '')}" /></div>
        <div class="form-row">
          <label>Expression language</label>
          <select id="prop-condition-language">
            <option value="feel" ${conditionLanguage === 'feel' ? 'selected' : ''}>FEEL</option>
            <option value="spel" ${conditionLanguage === 'spel' ? 'selected' : ''}>Legacy SpEL</option>
          </select>
        </div>
        <div class="form-row">
          <label>Condition expression</label>
          <textarea id="prop-condition" rows="4" placeholder="FEEL example: = approved = true">${escapeHtml(conditionValue)}</textarea>
          <div class="field-help">Use FEEL for new models. Legacy \${...} SpEL remains supported for older flows.</div>
        </div>
      `;
    } else {
      html = `
        <div class="form-row"><label>ID</label><input type="text" id="prop-id" value="${escapeHtml(businessObject.id || '')}" /></div>
        <div class="form-row"><label>Name</label><input type="text" id="prop-name" value="${escapeHtml(businessObject.name || '')}" /></div>
      `;

      if (isTaskLike) {
        html += `
          <div class="form-row">
            <label>Task kind</label>
            <select id="prop-task-kind">
              <option value="bpmn:Task" ${isGenericTask ? 'selected' : ''}>Generic task</option>
              <option value="bpmn:ServiceTask" ${isServiceTask ? 'selected' : ''}>Service task</option>
              <option value="bpmn:UserTask" ${isUserTask ? 'selected' : ''}>User task</option>
            </select>
          </div>
        `;
      }

      if (isServiceTask) {
        const serviceTaskMode = getServiceTaskMode(businessObject);
        const restConfig = getRestTaskConfig(businessObject);
        const beanConfig = getBeanTaskConfig(businessObject);
        const kafkaConfig = getKafkaTaskConfig(businessObject);
        html += `
          <div class="form-row">
            <label>Service task type</label>
            <select id="prop-service-task-mode">
              <option value="worker" ${serviceTaskMode === 'worker' ? 'selected' : ''}>Generic worker task</option>
              <option value="bean" ${serviceTaskMode === 'bean' ? 'selected' : ''}>Application bean task</option>
              <option value="rest" ${serviceTaskMode === 'rest' ? 'selected' : ''}>REST integration task</option>
              <option value="kafka" ${serviceTaskMode === 'kafka' ? 'selected' : ''}>Kafka publish task</option>
            </select>
          </div>
        `;

        if (serviceTaskMode === 'bean') {
          html += `
            <div class="field-group">
              <h4>Application Bean</h4>
              <div class="field-help">Choose a Spring bean implementing ServiceTaskLogic. Input mapping is resolved with FEEL and passed to the bean as named inputs.</div>
              <div class="form-row">
                <label>Bean</label>
                <select id="prop-bean-name">
                  <option value="">— Select bean —</option>
                  ${this.serviceTaskLogics.map((logic) => `<option value="${escapeHtml(logic.beanName)}" ${logic.beanName === beanConfig.beanName ? 'selected' : ''}>${escapeHtml(logic.displayName || logic.beanName)}</option>`).join('')}
                </select>
              </div>
              ${beanConfig.beanName ? `<div class="field-help">${escapeHtml(this.serviceTaskLogics.find((logic) => logic.beanName === beanConfig.beanName)?.description || '')}</div>` : ''}
              <div class="form-row"><label>Input mapping</label><textarea id="prop-bean-input-mapping" rows="5" placeholder='= { step: 1, customerId: customerId }'>${escapeHtml(beanConfig.inputMapping || '')}</textarea></div>
              <div class="form-row"><label>Result variable</label><input type="text" id="prop-bean-result-variable" value="${escapeHtml(beanConfig.resultVariable || '')}" placeholder="beanResult" /></div>
            </div>
          `;
        } else if (serviceTaskMode === 'rest') {
          html += `
            <div class="field-group">
              <h4>REST Integration</h4>
              <div class="field-help">Fields accept literals or FEEL expressions. Example URL: = "https://api.example.test/orders/" + orderId</div>
              <div class="form-row">
                <label>HTTP method</label>
                <select id="prop-rest-method">
                  ${['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((method) => `<option value="${method}" ${restConfig.method === method ? 'selected' : ''}>${method}</option>`).join('')}
                </select>
              </div>
              <div class="form-row"><label>URL</label><input type="text" id="prop-rest-url" value="${escapeHtml(restConfig.url || '')}" placeholder='= "https://api.example.test/orders/" + orderId' /></div>
              <div class="form-row">
                <label>Authentication</label>
                <select id="prop-rest-auth-type">
                  <option value="none" ${restConfig.authenticationType === 'none' ? 'selected' : ''}>Public / none</option>
                  <option value="bearer" ${restConfig.authenticationType === 'bearer' ? 'selected' : ''}>Bearer token</option>
                  <option value="basic" ${restConfig.authenticationType === 'basic' ? 'selected' : ''}>Basic auth</option>
                  <option value="apikey" ${restConfig.authenticationType === 'apikey' ? 'selected' : ''}>API key</option>
                </select>
              </div>
              <div class="form-row">
                <label>API key location</label>
                <select id="prop-rest-api-key-location">
                  <option value="header" ${restConfig.apiKeyLocation === 'header' ? 'selected' : ''}>Header</option>
                  <option value="query" ${restConfig.apiKeyLocation === 'query' ? 'selected' : ''}>Query parameter</option>
                </select>
              </div>
              <div class="form-row"><label>API key name</label><input type="text" id="prop-rest-api-key-name" value="${escapeHtml(restConfig.apiKeyName || '')}" placeholder="X-API-Key" /></div>
              <div class="form-row"><label>API key value</label><input type="text" id="prop-rest-api-key-value" value="${escapeHtml(restConfig.apiKeyValue || '')}" placeholder='= apiKey' /></div>
              <div class="form-row"><label>Username</label><input type="text" id="prop-rest-username" value="${escapeHtml(restConfig.username || '')}" placeholder='= username' /></div>
              <div class="form-row"><label>Password</label><input type="text" id="prop-rest-password" value="${escapeHtml(restConfig.password || '')}" placeholder='= password' /></div>
              <div class="form-row"><label>Bearer token</label><input type="text" id="prop-rest-bearer-token" value="${escapeHtml(restConfig.bearerToken || '')}" placeholder='= authToken' /></div>
              <div class="form-row"><label>Headers</label><textarea id="prop-rest-headers" rows="4" placeholder='= { Accept: "application/json" }'>${escapeHtml(restConfig.headers || '')}</textarea></div>
              <div class="form-row"><label>Query parameters</label><textarea id="prop-rest-query-parameters" rows="4" placeholder='= { orderId: orderId }'>${escapeHtml(restConfig.queryParameters || '')}</textarea></div>
              <div class="form-row"><label>Request body</label><textarea id="prop-rest-body" rows="5" placeholder='= { orderId: orderId, customerId: customerId }'>${escapeHtml(restConfig.body || '')}</textarea></div>
              <div class="form-row"><label>Result variable</label><input type="text" id="prop-rest-result-variable" value="${escapeHtml(restConfig.resultVariable || '')}" placeholder="apiResult" /></div>
              <div class="form-row"><label>Timeout (seconds)</label><input type="number" id="prop-rest-timeout-seconds" value="${escapeHtml(restConfig.timeoutSeconds ?? 20)}" min="0" /></div>
            </div>
          `;
        } else if (serviceTaskMode === 'kafka') {
          html += `
            <div class="field-group">
              <h4>Kafka Publish</h4>
              <div class="field-help">Map process variables to a Kafka message. Topic and messageMapping support FEEL/SpEL. Optional key for partitioning.</div>
              <div class="form-row"><label>Topic</label><input type="text" id="prop-kafka-topic" value="${escapeHtml(kafkaConfig.topic || '')}" placeholder="order-events" /></div>
              <div class="form-row"><label>Message mapping</label><textarea id="prop-kafka-message-mapping" rows="4" placeholder='= { orderId: orderId, amount: amount }'>${escapeHtml(kafkaConfig.messageMapping || '')}</textarea></div>
              <div class="form-row"><label>Key mapping (optional)</label><input type="text" id="prop-kafka-key-mapping" value="${escapeHtml(kafkaConfig.keyMapping || '')}" placeholder="orderId" /></div>
              <div class="form-row"><label>Result variable</label><input type="text" id="prop-kafka-result-variable" value="${escapeHtml(kafkaConfig.resultVariable || '')}" placeholder="kafkaResult" /></div>
            </div>
          `;
        } else {
          html += `
            <div class="form-row">
              <label>Implementation</label>
              <input type="text" id="prop-implementation" value="${escapeHtml(businessObject.implementation || '')}" placeholder="e.g. java, counter" />
            </div>
          `;
        }
      }

      if (isUserTask) {
        html += `
          <div class="form-row">
            <label>Assignee</label>
            <input type="text" id="prop-assignee" value="${escapeHtml(businessObject.assignee || '')}" placeholder="e.g. demo" />
          </div>
        `;
      }

      if (isStartEvent) {
        html += `
          <div class="field-group">
            <h4>Trigger</h4>
            <div class="field-help">Message or timer start. Leave empty for none.</div>
            <div class="form-row"><label>Message ref</label><input type="text" id="prop-engine-messageRef" value="${escapeHtml(engineCfg.messageRef || '')}" placeholder="OrderMessage" /></div>
            <div class="form-row"><label>Timer definition</label><input type="text" id="prop-engine-timerDefinition" value="${escapeHtml(engineCfg.timerDefinition || '')}" placeholder="PT5M or 300" /></div>
          </div>
        `;
      }
      if (isEndEvent) {
        html += `
          <div class="field-group">
            <h4>End type</h4>
            <div class="form-row"><label>Message ref</label><input type="text" id="prop-engine-messageRef" value="${escapeHtml(engineCfg.messageRef || '')}" placeholder="OrderCompleted" /></div>
            <div class="form-row"><label>Error code</label><input type="text" id="prop-engine-errorCode" value="${escapeHtml(engineCfg.errorCode || '')}" placeholder="ORDER_FAILED" /></div>
          </div>
        `;
      }
      if (isIntermediateCatchEvent) {
        html += `
          <div class="field-group">
            <h4>Catch</h4>
            <div class="form-row"><label>Message ref</label><input type="text" id="prop-engine-messageRef" value="${escapeHtml(engineCfg.messageRef || '')}" placeholder="OrderMessage" /></div>
            <div class="form-row"><label>Timer definition</label><input type="text" id="prop-engine-timerDefinition" value="${escapeHtml(engineCfg.timerDefinition || '')}" placeholder="PT30S" /></div>
          </div>
        `;
      }
      if (isIntermediateThrowEvent) {
        html += `
          <div class="field-group">
            <h4>Throw</h4>
            <div class="form-row"><label>Message ref</label><input type="text" id="prop-engine-messageRef" value="${escapeHtml(engineCfg.messageRef || '')}" placeholder="OrderSubmitted" /></div>
            <div class="form-row"><label>Signal ref</label><input type="text" id="prop-engine-signalRef" value="${escapeHtml(engineCfg.signalRef || '')}" placeholder="SignalName" /></div>
          </div>
        `;
      }
      if (isExclusiveGateway) {
        const outgoing = businessObject.outgoing || [];
        const defaultFlowId = businessObject.default?.id || engineCfg.defaultFlow || '';
        html += `
          <div class="form-row">
            <label>Default flow</label>
            <select id="prop-default">
              <option value="">— None</option>
              ${outgoing.map((flow) => `<option value="${escapeHtml(flow.id)}" ${flow.id === defaultFlowId ? 'selected' : ''}>${escapeHtml(flow.id)}</option>`).join('')}
            </select>
          </div>
        `;
      }
      if (isInclusiveGateway || isEventBasedGateway) {
        const outgoing = businessObject.outgoing || [];
        const defaultFlowId = businessObject.default?.id || engineCfg.defaultFlow || '';
        html += `
          <div class="form-row">
            <label>Default flow</label>
            <select id="prop-default">
              <option value="">— None</option>
              ${outgoing.map((flow) => `<option value="${escapeHtml(flow.id)}" ${flow.id === defaultFlowId ? 'selected' : ''}>${escapeHtml(flow.id)}</option>`).join('')}
            </select>
          </div>
        `;
      }
      if (isComplexGateway) {
        const outgoing = businessObject.outgoing || [];
        const defaultFlowId = businessObject.default?.id || engineCfg.defaultFlow || '';
        html += `
          <div class="form-row">
            <label>Default flow</label>
            <select id="prop-default">
              <option value="">— None</option>
              ${outgoing.map((flow) => `<option value="${escapeHtml(flow.id)}" ${flow.id === defaultFlowId ? 'selected' : ''}>${escapeHtml(flow.id)}</option>`).join('')}
            </select>
          </div>
          <div class="form-row"><label>Activation expression</label><textarea id="prop-engine-activationExpression" rows="3" placeholder="FEEL expression">${escapeHtml(engineCfg.activationExpression || '')}</textarea></div>
          <div class="form-row"><label>Activation language</label><input type="text" id="prop-engine-activationLanguage" value="${escapeHtml(engineCfg.activationLanguage || 'feel')}" /></div>
        `;
      }
    }

    this.propertyForm.innerHTML = html;
    const flags = {
      isProcess,
      isFlow,
      isGenericTask,
      isTaskLike,
      isServiceTask,
      isUserTask,
      isStartEvent,
      isEndEvent,
      isIntermediateCatchEvent,
      isIntermediateThrowEvent,
      isExclusiveGateway,
      isInclusiveGateway,
      isParallelGateway,
      isComplexGateway,
      isEventBasedGateway,
    };
    this.propertyForm.querySelectorAll('input, select, textarea').forEach((field) => {
      field.addEventListener('change', () => this.applyProperties(element, flags));
      field.addEventListener('blur', () => this.applyProperties(element, flags));
    });

    this.propertyForm.querySelector('#prop-task-kind')?.addEventListener('change', () => {
      this.applyProperties(element, flags);
    });

    this.propertyForm.querySelector('#prop-service-task-mode')?.addEventListener('change', () => {
      this.applyProperties(element, flags);
      this.renderProperties(element);
    });
  }

  applyProperties(element, flags) {
    if (!this.modeler || this.propertyForm.dataset.elementId !== element.id) {
      return;
    }

    const selectedElement = this.getSelectedElement();
    if (!selectedElement || selectedElement.id !== element.id) {
      return;
    }

    const businessObject = element.businessObject;
    const modeling = this.modeler.get('modeling');
    const moddle = this.modeler.get('moddle');

    if (flags.isTaskLike) {
      const requestedTaskKind = document.getElementById('prop-task-kind')?.value;
      if (requestedTaskKind && requestedTaskKind !== element.type) {
        const replacement = this.modeler.get('bpmnReplace').replaceElement(element, { type: requestedTaskKind });
        if (replacement) {
          this.renderProperties(replacement);
        }
        return;
      }
    }

    const idValue = document.getElementById('prop-id')?.value?.trim();
    const nameValue = document.getElementById('prop-name')?.value?.trim() || '';

    if (flags.isProcess) {
      if (idValue) {
        modeling.updateProperties(element, { id: idValue, name: nameValue });
      }
      return;
    }

    if (flags.isFlow) {
      if (idValue) {
        modeling.updateProperties(element, { id: idValue });
      }

      const conditionValue = document.getElementById('prop-condition')?.value?.trim() || '';
      const conditionLanguage = document.getElementById('prop-condition-language')?.value?.trim() || 'feel';
      modeling.updateProperties(element, {
        conditionExpression: conditionValue
          ? moddle.create('bpmn:FormalExpression', { body: conditionValue, language: conditionLanguage })
          : undefined,
      });
      return;
    }

    const properties = {};
    if (idValue) {
      properties.id = idValue;
    }
    properties.name = nameValue;

    if (flags.isServiceTask) {
      const serviceTaskMode = document.getElementById('prop-service-task-mode')?.value || 'worker';
      if (serviceTaskMode === 'rest') {
        properties.implementation = 'rest';
      } else if (serviceTaskMode === 'bean') {
        properties.implementation = 'bean';
      } else if (serviceTaskMode === 'kafka') {
        properties.implementation = 'kafka';
      } else {
        properties.implementation = document.getElementById('prop-implementation')?.value?.trim() || '';
      }
    }

    if (flags.isUserTask) {
      properties.assignee = document.getElementById('prop-assignee')?.value?.trim() || undefined;
    }

    if (flags.isExclusiveGateway || flags.isInclusiveGateway || flags.isEventBasedGateway || flags.isComplexGateway) {
      const defaultId = document.getElementById('prop-default')?.value?.trim() || '';
      properties.default = defaultId ? (businessObject.outgoing || []).find((flow) => flow.id === defaultId) : undefined;
      this.engineConfigByElementId[element.id] = {
        ...this.engineConfigByElementId[element.id],
        defaultFlow: defaultId || undefined,
      };
    }
    if (flags.isComplexGateway) {
      this.engineConfigByElementId[element.id] = {
        ...this.engineConfigByElementId[element.id],
        activationExpression: document.getElementById('prop-engine-activationExpression')?.value?.trim() || undefined,
        activationLanguage: document.getElementById('prop-engine-activationLanguage')?.value?.trim() || undefined,
      };
    }
    if (flags.isStartEvent) {
      this.engineConfigByElementId[element.id] = {
        ...this.engineConfigByElementId[element.id],
        messageRef: document.getElementById('prop-engine-messageRef')?.value?.trim() || undefined,
        timerDefinition: document.getElementById('prop-engine-timerDefinition')?.value?.trim() || undefined,
      };
    }
    if (flags.isEndEvent) {
      this.engineConfigByElementId[element.id] = {
        ...this.engineConfigByElementId[element.id],
        messageRef: document.getElementById('prop-engine-messageRef')?.value?.trim() || undefined,
        errorCode: document.getElementById('prop-engine-errorCode')?.value?.trim() || undefined,
      };
    }
    if (flags.isIntermediateCatchEvent) {
      this.engineConfigByElementId[element.id] = {
        ...this.engineConfigByElementId[element.id],
        messageRef: document.getElementById('prop-engine-messageRef')?.value?.trim() || undefined,
        timerDefinition: document.getElementById('prop-engine-timerDefinition')?.value?.trim() || undefined,
      };
    }
    if (flags.isIntermediateThrowEvent) {
      this.engineConfigByElementId[element.id] = {
        ...this.engineConfigByElementId[element.id],
        messageRef: document.getElementById('prop-engine-messageRef')?.value?.trim() || undefined,
        signalRef: document.getElementById('prop-engine-signalRef')?.value?.trim() || undefined,
      };
    }

    modeling.updateProperties(element, properties);

    if (flags.isServiceTask) {
      const serviceTaskMode = document.getElementById('prop-service-task-mode')?.value || 'worker';
      if (serviceTaskMode === 'rest') {
        this.upsertEngineTaskConfiguration(element, {
          type: 'rest',
          method: document.getElementById('prop-rest-method')?.value?.trim() || 'GET',
          url: document.getElementById('prop-rest-url')?.value?.trim() || '',
          authenticationType: document.getElementById('prop-rest-auth-type')?.value?.trim() || 'none',
          apiKeyLocation: document.getElementById('prop-rest-api-key-location')?.value?.trim() || 'header',
          apiKeyName: document.getElementById('prop-rest-api-key-name')?.value?.trim() || '',
          apiKeyValue: document.getElementById('prop-rest-api-key-value')?.value?.trim() || '',
          username: document.getElementById('prop-rest-username')?.value?.trim() || '',
          password: document.getElementById('prop-rest-password')?.value?.trim() || '',
          bearerToken: document.getElementById('prop-rest-bearer-token')?.value?.trim() || '',
          headers: document.getElementById('prop-rest-headers')?.value?.trim() || '',
          queryParameters: document.getElementById('prop-rest-query-parameters')?.value?.trim() || '',
          body: document.getElementById('prop-rest-body')?.value?.trim() || '',
          resultVariable: document.getElementById('prop-rest-result-variable')?.value?.trim() || '',
          timeoutSeconds: parseInt(document.getElementById('prop-rest-timeout-seconds')?.value || '20', 10) || 20,
        });
      } else if (serviceTaskMode === 'bean') {
        this.upsertEngineTaskConfiguration(element, {
          type: 'bean',
          beanName: document.getElementById('prop-bean-name')?.value?.trim() || '',
          inputMapping: document.getElementById('prop-bean-input-mapping')?.value?.trim() || '',
          resultVariable: document.getElementById('prop-bean-result-variable')?.value?.trim() || '',
        });
      } else if (serviceTaskMode === 'kafka') {
        this.upsertEngineTaskConfiguration(element, {
          type: 'kafka',
          topic: document.getElementById('prop-kafka-topic')?.value?.trim() || '',
          messageMapping: document.getElementById('prop-kafka-message-mapping')?.value?.trim() || '',
          keyMapping: document.getElementById('prop-kafka-key-mapping')?.value?.trim() || '',
          resultVariable: document.getElementById('prop-kafka-result-variable')?.value?.trim() || '',
        });
      } else {
        this.removeEngineTaskConfiguration(element);
      }
    }
  }

  upsertEngineTaskConfiguration(element, properties) {
    const modeling = this.modeler.get('modeling');
    const moddle = this.modeler.get('moddle');
    const businessObject = element.businessObject;

    let extensionElements = businessObject.extensionElements;
    if (!extensionElements) {
      extensionElements = moddle.create('bpmn:ExtensionElements', { values: [] });
      modeling.updateProperties(element, { extensionElements });
      extensionElements = element.businessObject.extensionElements;
    }

    let taskConfiguration = getEngineTaskConfiguration(element.businessObject);
    if (!taskConfiguration) {
      taskConfiguration = moddle.create(ENGINE_TASK_CONFIG_TYPE, properties);
      modeling.updateModdleProperties(element, extensionElements, {
        values: [...(extensionElements.values || []), taskConfiguration],
      });
      return;
    }

    modeling.updateModdleProperties(element, taskConfiguration, properties);
  }

  removeEngineTaskConfiguration(element) {
    const modeling = this.modeler.get('modeling');
    const businessObject = element.businessObject;
    const extensionElements = businessObject.extensionElements;

    if (!extensionElements?.values?.length) {
      return;
    }

    const remainingValues = extensionElements.values.filter((value) => value.$type !== ENGINE_TASK_CONFIG_TYPE);
    modeling.updateModdleProperties(element, extensionElements, { values: remainingValues });
  }
}
