export function getBpmnModelerConstructor() {
  return window.BpmnModeler || window.BpmnJS;
}

export async function getBpmnViewerConstructor() {
  return window.BpmnViewer || null;
}
