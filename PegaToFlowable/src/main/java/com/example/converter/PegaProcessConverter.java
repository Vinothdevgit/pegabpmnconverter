package com.example.converter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.converter.BpmnXMLConverter;

public class PegaProcessConverter {

    public static void main(String[] args) throws Exception {
        // Determine input and output file paths
        String inputFile = (args.length > 0) ? args[0] : "SamplePegaProcess.xml";
        String outputFile = (args.length > 1) ? args[1] : "output.bpmn20.xml";

        // Parse the Pega process XML file using DOM
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(inputFile));
        doc.getDocumentElement().normalize();

        // Locate the pyModelProcess/pyShapes and pyConnectors sections
        Element modelProcess = (Element) doc.getElementsByTagName("pyModelProcess").item(0);
        if (modelProcess == null) {
            System.err.println("ERROR: No pyModelProcess section found in the input file.");
            return;
        }
        Element shapesParent = (Element) modelProcess.getElementsByTagName("pyShapes").item(0);
        Element connectorsParent = (Element) modelProcess.getElementsByTagName("pyConnectors").item(0);
        if (shapesParent == null) {
            System.err.println("ERROR: No pyShapes section found in the input file.");
            return;
        }

        // Prepare a BPMN model and process
        BpmnModel bpmnModel = new BpmnModel();
        Process process = new Process();
        process.setId("convertedProcess");
        process.setName("Converted Process");
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        // Data structures to hold shapes and flows
        List<Element> shapeElements = new ArrayList<>();
        Map<String, FlowNode> flowNodeMap = new HashMap<>();

        // Collect all shape rowdata elements (immediate children of pyShapes)
        NodeList shapeNodes = shapesParent.getChildNodes();
        for (int i = 0; i < shapeNodes.getLength(); i++) {
            Node node = shapeNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "rowdata".equals(node.getNodeName())) {
                shapeElements.add((Element) node);
            }
        }

        // Sort shapes by Y then X coordinate for visual ordering
        shapeElements.sort((e1, e2) -> {
            double y1 = parseDoubleSafe(getChildElementText(e1, "pyCoordY"), 0.0);
            double y2 = parseDoubleSafe(getChildElementText(e2, "pyCoordY"), 0.0);
            if (Double.compare(y1, y2) != 0) {
                return Double.compare(y1, y2);
            }
            double x1 = parseDoubleSafe(getChildElementText(e1, "pyCoordX"), 0.0);
            double x2 = parseDoubleSafe(getChildElementText(e2, "pyCoordX"), 0.0);
            return Double.compare(x1, x2);
        });

        // Create Flowable elements for each shape
        for (Element shapeElem : shapeElements) {
            String shapeId = getChildElementText(shapeElem, "pyMOId");
            String shapeName = getChildElementText(shapeElem, "pyMOName");
            String shapeType = getChildElementText(shapeElem, "pxObjClass");
            if (shapeId == null || shapeType == null) {
                continue; // skip if essential data missing
            }
            FlowNode flowNode = null;
            // Map known Pega shape types to Flowable BPMN elements
            switch (shapeType) {
                case "Data-MO-Event-Start":
                    StartEvent startEvent = new StartEvent();
                    startEvent.setId(shapeId);
                    // If no name provided for start, assign a default name
                    startEvent.setName((shapeName == null || shapeName.isEmpty()) ? "Start" : shapeName);
                    flowNode = startEvent;
                    break;
                case "Data-MO-Event-End":
                    EndEvent endEvent = new EndEvent();
                    endEvent.setId(shapeId);
                    endEvent.setName((shapeName == null || shapeName.isEmpty()) ? "End" : shapeName);
                    flowNode = endEvent;
                    break;
                case "Data-MO-Assignment-Work":
                    UserTask userTask = new UserTask();
                    userTask.setId(shapeId);
                    if (shapeName != null && !shapeName.isEmpty()) {
                        userTask.setName(shapeName);
                    }
                    flowNode = userTask;
                    break;
                case "Data-MO-Activity-Utility":
                    ServiceTask serviceTask = new ServiceTask();
                    serviceTask.setId(shapeId);
                    if (shapeName != null && !shapeName.isEmpty()) {
                        serviceTask.setName(shapeName);
                    }
                    flowNode = serviceTask;
                    break;
                case "Data-MO-Gateway-Decision":
                    ExclusiveGateway exclGateway = new ExclusiveGateway();
                    exclGateway.setId(shapeId);
                    // Gateways can have a name (optional)
                    if (shapeName != null && !shapeName.isEmpty()) {
                        exclGateway.setName(shapeName);
                    }
                    flowNode = exclGateway;
                    break;
                case "Data-MO-Gateway-Parallel":
                    ParallelGateway parallelGateway = new ParallelGateway();
                    parallelGateway.setId(shapeId);
                    if (shapeName != null && !shapeName.isEmpty()) {
                        parallelGateway.setName(shapeName);
                    }
                    flowNode = parallelGateway;
                    break;
                case "Data-MO-Subprocess-Call":
                case "Data-MO-Activity-SubProcess":
                    CallActivity callActivity = new CallActivity();
                    callActivity.setId(shapeId);
                    if (shapeName != null && !shapeName.isEmpty()) {
                        callActivity.setName(shapeName);
                    }
                    // Set the called element (subprocess ID) if available
                    String calledElement = getChildElementText(shapeElem, "pyImplementation");
                    if (calledElement != null && !calledElement.isEmpty()) {
                        callActivity.setCalledElement(calledElement);
                    }
                    flowNode = callActivity;
                    break;
                default:
                    // Unmapped shape type
                    System.out.println("Skipping unmapped shape type: " + shapeType
                            + " (ID=" + shapeId + ", Name=" + (shapeName != null ? shapeName : "") + ")");
                    break;
            }
            if (flowNode != null) {
                process.addFlowElement(flowNode);
                flowNodeMap.put(shapeId, flowNode);
            }
        }

        if (connectorsParent != null) {
            // Process each connector to create sequence flows
            NodeList connectorNodes = connectorsParent.getChildNodes();
            for (int i = 0; i < connectorNodes.getLength(); i++) {
                Node node = connectorNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE || !"rowdata".equals(node.getNodeName())) {
                    continue;
                }
                Element connElem = (Element) node;
                String connId = getChildElementText(connElem, "pyMOId");
                String fromId = getChildElementText(connElem, "pyFrom");
                String toId = getChildElementText(connElem, "pyTo");
                String connType = getChildElementText(connElem, "pxObjClass");
                String connName = getChildElementText(connElem, "pyMOName");
                String condType = getChildElementText(connElem, "pyConditionType");
                if (connType == null || !connType.equals("Data-MO-Connector-Transition")) {
                    // Only handle Transition connectors for sequence flows
                    System.out.println("Skipping unmapped connector type: " + connType
                            + " (ID=" + (connId != null ? connId : "unknown") + ")");
                    continue;
                }
                if (fromId == null || toId == null) {
                    System.out.println("Skipping connector (ID=" + (connId != null ? connId : "unknown")
                            + ") due to missing source/target reference.");
                    continue;
                }
                // Ensure source and target shapes exist
                if (!flowNodeMap.containsKey(fromId) || !flowNodeMap.containsKey(toId)) {
                    System.out.println("Skipping connector (ID=" + (connId != null ? connId : "unknown") + ", Name="
                            + (connName != null ? connName : "") + ") due to missing shape (from="
                            + fromId + ", to=" + toId + ").");
                    continue;
                }
                // Create SequenceFlow linking source to target
                SequenceFlow seqFlow = new SequenceFlow(fromId, toId);
                String sequenceId = (connId != null && !connId.isEmpty()) ? connId : ("flow_" + fromId + "_" + toId);
                seqFlow.setId(sequenceId);
                if (connName != null && !connName.isEmpty()) {
                    seqFlow.setName(connName);
                }
                process.addFlowElement(seqFlow);
                // If source is an exclusive gateway, handle default flow if applicable
                FlowNode sourceNode = flowNodeMap.get(fromId);
                if (sourceNode instanceof ExclusiveGateway) {
                    // Mark default flow for "Else" or unconditional paths
                    if ("Else".equalsIgnoreCase(condType) || "Always".equalsIgnoreCase(condType)
                            || "[Else]".equalsIgnoreCase(connName) || "[Always]".equalsIgnoreCase(connName)) {
                        ((ExclusiveGateway) sourceNode).setDefaultFlow(sequenceId);
                    }
                }
            }
        }

        // Convert BpmnModel to XML and write to file
        BpmnXMLConverter xmlConverter = new BpmnXMLConverter();
        byte[] xmlBytes = xmlConverter.convertToXML(bpmnModel);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(xmlBytes);
        }
        System.out.println("BPMN model successfully written to " + outputFile);
    }

    // Utility method to get text content of a child element by tag name
    private static String getChildElementText(Element parent, String tagName) {
        NodeList nodeList = parent.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            Node node = nodeList.item(0);
            // Ensure this node is a direct child of the parent to avoid nested elements
            if (node.getParentNode() == parent) {
                return node.getTextContent();
            }
        }
        return null;
    }

    // Utility to safely parse a string to double (returns defaultVal if null/empty or parse fails)
    private static double parseDoubleSafe(String text, double defaultVal) {
        if (text == null || text.isEmpty()) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
