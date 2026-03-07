// @ts-check
/** BPMN 2.0 XML fixtures for E2E tests (live backend). All use bpmndi for viewer. */

const NS = 'xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI"';
const ENGINE_NS = 'xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0"';
const CAMUNDA_NS = 'xmlns:camunda="http://camunda.org/schema/1.0/bpmn"';

/** Minimal: start -> java service -> end */
const MINIMAL_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS}>
  <bpmn:process id="Process_Minimal" name="Minimal">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1" name="Do Work" implementation="java">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_Minimal" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="StartEvent_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_1" bpmnElement="Task_1"><dc:Bounds x="248" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="EndEvent_1"><dc:Bounds x="398" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** User task: start -> java -> user task -> end */
const USER_TASK_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS} ${CAMUNDA_NS}>
  <bpmn:process id="Process_UserTask" name="User Task">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1" name="Prepare" implementation="java">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:userTask id="UserTask_1" name="Approve" camunda:assignee="user">
      <bpmn:incoming>Flow_2</bpmn:incoming><bpmn:outgoing>Flow_3</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="UserTask_1"/>
    <bpmn:sequenceFlow id="Flow_3" sourceRef="UserTask_1" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_UserTask" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="StartEvent_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_1" bpmnElement="Task_1"><dc:Bounds x="248" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_UserTask_1" bpmnElement="UserTask_1"><dc:Bounds x="398" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="EndEvent_1"><dc:Bounds x="548" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** Exclusive gateway: start -> java -> gateway (flag) -> yes/no/default -> end */
const EXCLUSIVE_GATEWAY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS} ${CAMUNDA_NS}>
  <bpmn:process id="Process_XOR" name="Exclusive Gateway">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1" name="Prepare" implementation="java">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:exclusiveGateway id="Gateway_1" name="Check" default="Flow_default">
      <bpmn:incoming>Flow_2</bpmn:incoming>
      <bpmn:outgoing>Flow_yes</bpmn:outgoing><bpmn:outgoing>Flow_no</bpmn:outgoing><bpmn:outgoing>Flow_default</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:serviceTask id="Task_yes" name="Yes" implementation="java">
      <bpmn:incoming>Flow_yes</bpmn:incoming><bpmn:outgoing>Flow_3</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="Task_no" name="No" implementation="java">
      <bpmn:incoming>Flow_no</bpmn:incoming><bpmn:outgoing>Flow_4</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="EndEvent_1">
      <bpmn:incoming>Flow_3</bpmn:incoming><bpmn:incoming>Flow_4</bpmn:incoming><bpmn:incoming>Flow_default</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="Gateway_1"/>
    <bpmn:sequenceFlow id="Flow_yes" sourceRef="Gateway_1" targetRef="Task_yes"><bpmn:conditionExpression>\${flag == true}</bpmn:conditionExpression></bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_no" sourceRef="Gateway_1" targetRef="Task_no"><bpmn:conditionExpression>\${flag == false}</bpmn:conditionExpression></bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_default" sourceRef="Gateway_1" targetRef="EndEvent_1"/>
    <bpmn:sequenceFlow id="Flow_3" sourceRef="Task_yes" targetRef="EndEvent_1"/>
    <bpmn:sequenceFlow id="Flow_4" sourceRef="Task_no" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_XOR" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="StartEvent_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_1" bpmnElement="Task_1"><dc:Bounds x="248" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Gateway_1" bpmnElement="Gateway_1"><dc:Bounds x="398" y="75" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_yes" bpmnElement="Task_yes"><dc:Bounds x="498" y="40" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_no" bpmnElement="Task_no"><dc:Bounds x="498" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="EndEvent_1"><dc:Bounds x="648" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** Parallel gateway: start -> split -> A & B -> join -> end */
const PARALLEL_GATEWAY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS}>
  <bpmn:process id="Process_Parallel" name="Parallel">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:parallelGateway id="Gateway_split">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing><bpmn:outgoing>Flow_3</bpmn:outgoing>
    </bpmn:parallelGateway>
    <bpmn:serviceTask id="Task_A" name="A" implementation="java">
      <bpmn:incoming>Flow_2</bpmn:incoming><bpmn:outgoing>Flow_4</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="Task_B" name="B" implementation="java">
      <bpmn:incoming>Flow_3</bpmn:incoming><bpmn:outgoing>Flow_5</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:parallelGateway id="Gateway_join">
      <bpmn:incoming>Flow_4</bpmn:incoming><bpmn:incoming>Flow_5</bpmn:incoming><bpmn:outgoing>Flow_6</bpmn:outgoing>
    </bpmn:parallelGateway>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_6</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Gateway_split"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_split" targetRef="Task_A"/>
    <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_split" targetRef="Task_B"/>
    <bpmn:sequenceFlow id="Flow_4" sourceRef="Task_A" targetRef="Gateway_join"/>
    <bpmn:sequenceFlow id="Flow_5" sourceRef="Task_B" targetRef="Gateway_join"/>
    <bpmn:sequenceFlow id="Flow_6" sourceRef="Gateway_join" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_Parallel" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="StartEvent_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Gateway_split" bpmnElement="Gateway_split"><dc:Bounds x="248" y="75" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_A" bpmnElement="Task_A"><dc:Bounds x="348" y="40" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_B" bpmnElement="Task_B"><dc:Bounds x="348" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Gateway_join" bpmnElement="Gateway_join"><dc:Bounds x="498" y="75" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="EndEvent_1"><dc:Bounds x="598" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** Message start event: start(messageRef) -> java -> end */
const MESSAGE_START_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS} ${ENGINE_NS}>
  <bpmn:process id="Process_MessageStart" name="Message Start">
    <bpmn:startEvent id="Start_1" engine:messageRef="OrderReceived">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:serviceTask id="Task_1" name="Handle" implementation="java">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="End_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_MessageStart" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="Start_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_1" bpmnElement="Task_1"><dc:Bounds x="248" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="End_1"><dc:Bounds x="398" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** Intermediate catch: start -> java -> catch(messageRef) -> java -> end */
const CATCH_EVENT_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS} ${ENGINE_NS}>
  <bpmn:process id="Process_Catch" name="Catch">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1" name="Before" implementation="java">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:intermediateCatchEvent id="Catch_1" engine:messageRef="Reply">
      <bpmn:incoming>Flow_2</bpmn:incoming><bpmn:outgoing>Flow_3</bpmn:outgoing>
    </bpmn:intermediateCatchEvent>
    <bpmn:serviceTask id="Task_2" name="After" implementation="java">
      <bpmn:incoming>Flow_3</bpmn:incoming><bpmn:outgoing>Flow_4</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_4</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="Catch_1"/>
    <bpmn:sequenceFlow id="Flow_3" sourceRef="Catch_1" targetRef="Task_2"/>
    <bpmn:sequenceFlow id="Flow_4" sourceRef="Task_2" targetRef="End_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_Catch" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="Start_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_1" bpmnElement="Task_1"><dc:Bounds x="248" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Catch_1" bpmnElement="Catch_1"><dc:Bounds x="398" y="72" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_2" bpmnElement="Task_2"><dc:Bounds x="494" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="End_1"><dc:Bounds x="644" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** Intermediate throw (message): start -> throw(messageRef) -> end */
const THROW_EVENT_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS} ${ENGINE_NS}>
  <bpmn:process id="Process_Throw" name="Throw">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:intermediateThrowEvent id="Throw_1" engine:messageRef="Outbound">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:intermediateThrowEvent>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Throw_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Throw_1" targetRef="End_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_Throw" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="Start_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Throw_1" bpmnElement="Throw_1"><dc:Bounds x="248" y="72" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="End_1"><dc:Bounds x="334" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

/** Kafka service task: start -> kafka task (topic, messageMapping, keyMapping, resultVariable) -> end */
const KAFKA_TASK_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions ${NS} ${ENGINE_NS}>
  <bpmn:process id="Process_KafkaTask" name="Kafka Task">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_Kafka" name="Publish Order" implementation="kafka">
      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
      <bpmn:extensionElements>
        <engine:taskConfiguration type="kafka" topic="e2e-orders" messageMapping="= { orderId: orderId, amount: amount }" keyMapping="orderId" resultVariable="kafkaResult" />
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Kafka"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Kafka" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="Process_KafkaTask" id="BPMNPlane_1">
      <bpmndi:BPMNShape id="Shape_Start_1" bpmnElement="StartEvent_1"><dc:Bounds x="152" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_Kafka" bpmnElement="Task_Kafka"><dc:Bounds x="248" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_End_1" bpmnElement="EndEvent_1"><dc:Bounds x="398" y="82" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

module.exports = {
  MINIMAL_BPMN,
  USER_TASK_BPMN,
  EXCLUSIVE_GATEWAY_BPMN,
  PARALLEL_GATEWAY_BPMN,
  MESSAGE_START_BPMN,
  CATCH_EVENT_BPMN,
  THROW_EVENT_BPMN,
  KAFKA_TASK_BPMN,
};
