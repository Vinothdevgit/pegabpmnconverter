package com.example.converter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.*;
import java.util.*;

public class PegaToBPMNConverter {

    // Utility: get first child element by tag name within a parent element
    private static Node getChildNode(Node parent, String name) {
        if (parent == null) return null;
        NodeList children = ((Element) parent).getElementsByTagName(name);
        return (children.getLength() > 0) ? children.item(0) : null;
    }

    // Utility: escape XML special characters in text (for safe XML attributes)
    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java PegaToBPMNConverter <inputPegaXml> <outputBpmnXml>");
        }
        String inputPath = "SamplePegaProcess.xml";
        String outputPath = "OutputFlowable.bpmn20.xml";
        try {
            // Parse the Pega XML file into a DOM Document
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringComments(true);
            try {
                // Disable DTD loading for safety
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception e) {
                // Ignore if not supported
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new File(inputPath));
            doc.getDocumentElement().normalize();

            // Mapping from Pega pxObjClass to BPMN element type
            Map<String, String> classToBpmnType = new HashMap<>();
            classToBpmnType.put("Data-MO-Event-Start", "startEvent");
            classToBpmnType.put("Data-MO-Event-End", "endEvent");
            classToBpmnType.put("Data-MO-Activity-Assignment", "userTask");
            classToBpmnType.put("Data-MO-Activity-Router", "serviceTask");
            classToBpmnType.put("Data-MO-Activity-Notify", "scriptTask");
            classToBpmnType.put("Data-MO-Event-Exception", "boundaryEvent");
            classToBpmnType.put("Data-MO-Activity-Utility", "serviceTask");
            classToBpmnType.put("Data-MO-Gateway-Decision", "exclusiveGateway");
            classToBpmnType.put("Data-MO-Activity-SubProcess", "callActivity");
            // Note: Data-MO-Connector-Transition (sequence flows) handled separately

            // Structures to hold parsed shapes and connectors
            class ShapeInfo {
                String pegaId;
                String bpmnId;
                String bpmnType;
                String name;
                int x, y, width, height;
                String attachedToRef;  // For boundary events (attached activity's BPMN id)
            }
            class ConnectorInfo {
                String fromId;
                String toId;
                String name;
                String bpmnId;
            }

            List<ShapeInfo> shapesList = new ArrayList<>();
            Map<String, ShapeInfo> shapeMap = new HashMap<>();
            List<ConnectorInfo> connectorList = new ArrayList<>();

            // Traverse all pxObjClass elements in the XML
            NodeList pxClasses = doc.getElementsByTagName("pxObjClass");
            for (int i = 0; i < pxClasses.getLength(); i++) {
                Node classNode = pxClasses.item(i);
                if (classNode.getNodeType() != Node.ELEMENT_NODE) continue;
                String classVal = classNode.getTextContent().trim();
                if (!classVal.startsWith("Data-MO-")) {
                    // Ignore any non-flow elements
                    continue;
                }
                Node parent = classNode.getParentNode();  // the <rowdata> element
                if (classVal.equals("Data-MO-Connector-Transition")) {
                    // Found a connector (sequence flow)
                    ConnectorInfo ci = new ConnectorInfo();
                    Node fromNode = getChildNode(parent, "pyFrom");
                    Node toNode = getChildNode(parent, "pyTo");
                    if (fromNode == null || toNode == null) {
                        continue;  // skip if connector is malformed
                    }
                    ci.fromId = fromNode.getTextContent().trim();
                    ci.toId = toNode.getTextContent().trim();
                    Node nameNode = getChildNode(parent, "pyMOName");
                    if (nameNode != null) {
                        String cname = nameNode.getTextContent().trim();
                        ci.name = cname.isEmpty() ? null : cname;
                    }
                    connectorList.add(ci);
                } else {
                    // Found a shape (Start, Task, End, etc.)
                    String bpmnType = classToBpmnType.get(classVal);
                    if (bpmnType == null) {
                        // Unknown Data-MO class – skip it
                        System.out.println("Skipping pxObjClass: " + classVal);
                        continue;
                    }
                    ShapeInfo si = new ShapeInfo();
                    Node idNode = getChildNode(parent, "pyMOId");
                    si.pegaId = (idNode != null) ? idNode.getTextContent().trim() : UUID.randomUUID().toString();
                    // Create a safe BPMN id (valid XML NCName)
                    String safeId = si.pegaId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                    if (safeId.isEmpty()) {
                        safeId = "id_" + UUID.randomUUID().toString().replace("-", "");
                    } else if (!Character.isLetter(safeId.charAt(0)) && safeId.charAt(0) != '_') {
                        safeId = "id_" + safeId;
                    }
                    si.bpmnId = safeId;
                    si.bpmnType = bpmnType;
                    Node nameNode = getChildNode(parent, "pyMOName");
                    si.name = (nameNode != null && !nameNode.getTextContent().trim().isEmpty())
                            ? nameNode.getTextContent().trim() : null;
                    // Coordinates (with offset and scale for layout)
                    Node xNode = getChildNode(parent, "pyCoordX");
                    Node yNode = getChildNode(parent, "pyCoordY");
                    double px = parseDoubleSafe((xNode != null) ? xNode.getTextContent() : null, 0.0);
                    double py = parseDoubleSafe((yNode != null) ? yNode.getTextContent() : null, 0.0);
                    int offset = 5, scale = 120;
                    si.x = (int) ((px + offset) * scale);
                    si.y = (int) ((py + offset) * scale);
                    // Set default shape size by type
                    if (bpmnType.equals("startEvent") || bpmnType.equals("endEvent") || bpmnType.equals("boundaryEvent")) {
                        si.width = 36;
                        si.height = 36;
                    } else if (bpmnType.equals("userTask") || bpmnType.equals("serviceTask") || bpmnType.equals("scriptTask")) {
                        si.width = 100;
                        si.height = 80;
                    } else {
                        si.width = 50;
                        si.height = 50;
                    }
                    si.attachedToRef = null;
                    shapesList.add(si);
                    shapeMap.put(si.pegaId, si);
                }
            }

            // Process connectors: create sequence flows and handle boundary event attachments
            List<ConnectorInfo> flowList = new ArrayList<>();
            Map<String, String> attachmentLabelMap = new HashMap<>();  // store labels of attached error connectors
            int flowCounter = 1;
            for (ConnectorInfo ci : connectorList) {
                ShapeInfo fromShape = shapeMap.get(ci.fromId);
                ShapeInfo toShape = shapeMap.get(ci.toId);
                if (toShape != null && "boundaryEvent".equals(toShape.bpmnType)) {
                    // Connector leads to an exception event – treat as attaching a boundary event
                    if (fromShape != null) {
                        toShape.attachedToRef = fromShape.bpmnId;
                        if (ci.name != null) {
                            attachmentLabelMap.put(ci.toId, ci.name);  // store boundary label for later
                        }
                    }
                    // Skip adding a sequence flow for this attachment
                    continue;
                }
                if (fromShape == null || toShape == null) {
                    // If either end of the connector was skipped or missing, skip this flow
                    if (fromShape == null) {
                        System.out.println("Skipping connector from shape " + ci.fromId + " (not processed)");
                    }
                    if (toShape == null) {
                        System.out.println("Skipping connector to shape " + ci.toId + " (not processed)");
                    }
                    continue;
                }
                // Normal sequence flow
                ci.bpmnId = "Flow_" + flowCounter++;
                flowList.add(ci);
            }

            // Build BPMN XML output
            StringBuilder bpmnXml = new StringBuilder();
            bpmnXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            bpmnXml.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" ");
            bpmnXml.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
            bpmnXml.append("xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" ");
            bpmnXml.append("xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" ");
            bpmnXml.append("xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" ");
            bpmnXml.append("targetNamespace=\"http://flowable.org/process\">\n");

            // Determine process name and ID
            Element root = doc.getDocumentElement();
            String processName = null;
            if (root.hasAttribute("name")) {
                processName = root.getAttribute("name").trim();
            }
            if (processName == null || processName.isEmpty()) {
                Node labelNode = getChildNode(root, "pyLabel");
                if (labelNode != null) {
                    processName = labelNode.getTextContent().trim();
                }
            }
            if (processName == null || processName.isEmpty()) {
                Node flowNameNode = getChildNode(root, "pyFlowName");
                if (flowNameNode != null) {
                    processName = flowNameNode.getTextContent().trim();
                }
            }
            if (processName == null || processName.isEmpty()) {
                // Fallback to input file name (without extension) if no name found
                String fileName = new File(inputPath).getName();
                int dotIndex = fileName.lastIndexOf('.');
                processName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
            }
            String processId = processName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (!Character.isLetter(processId.charAt(0)) && processId.charAt(0) != '_') {
                processId = "Process_" + processId;
            }

            // Begin process element
            bpmnXml.append("  <process id=\"").append(processId)
                    .append("\" name=\"").append(escapeXml(processName))
                    .append("\" isExecutable=\"true\">\n");
            // Add BPMN elements for each shape
            Set<String> writtenShapes = new HashSet<>();
            Map<String, ShapeInfo> uniqueShapes = new LinkedHashMap<>();
            for (ShapeInfo shape : shapesList) {
                uniqueShapes.put(shape.bpmnId, shape); // overwrite any duplicate BPMN ID
            }
            shapesList = new ArrayList<>(uniqueShapes.values());
            for (ShapeInfo si : shapesList) {
                if (!writtenShapes.add(si.bpmnId)) {
                    System.out.println("⚠️ Skipping duplicate BPMNShape for: " + si.bpmnId);
                    continue;
                }
                String idAttr = "id=\"" + si.bpmnId + "\"";
                String nameAttr = (si.name != null ? " name=\"" + escapeXml(si.name) + "\"" : "");
                if ("boundaryEvent".equals(si.bpmnType)) {
                    if (si.attachedToRef == null) {
                        System.out.println("⚠️ Skipping boundaryEvent with no attachedToRef: " + si.bpmnId);
                        continue; // skip writing invalid boundary events
                    }
                    bpmnXml.append("    <boundaryEvent ")
                            .append(idAttr).append(nameAttr)
                            .append(" attachedToRef=\"").append(si.attachedToRef)
                            .append("\" cancelActivity=\"true\">\n")
                            .append("      <errorEventDefinition/>\n")
                            .append("    </boundaryEvent>\n");
                }
                else {
                    // Regular task or event
                    bpmnXml.append("    <").append(si.bpmnType).append(" ")
                            .append(idAttr).append(nameAttr).append("/>\n");
                }
            }
            // Add BPMN elements for each sequence flow
            for (ConnectorInfo ci : flowList) {
                ShapeInfo fromShape = shapeMap.get(ci.fromId);
                ShapeInfo toShape = shapeMap.get(ci.toId);
                if (fromShape == null || toShape == null) continue;
                bpmnXml.append("    <sequenceFlow id=\"").append(ci.bpmnId)
                        .append("\" sourceRef=\"").append(fromShape.bpmnId)
                        .append("\" targetRef=\"").append(toShape.bpmnId).append("\"");
                // Apply name if present or if coming from a boundary event with an attachment label
                String flowName = ci.name;
                if ((flowName == null || flowName.isEmpty()) && "boundaryEvent".equals(fromShape.bpmnType)) {
                    String attachLabel = attachmentLabelMap.get(fromShape.pegaId);
                    if (attachLabel != null && !attachLabel.isEmpty()) {
                        flowName = attachLabel;
                    }
                }
                if (flowName != null && !flowName.isEmpty()) {
                    bpmnXml.append(" name=\"").append(escapeXml(flowName)).append("\"");
                }
                bpmnXml.append("/>\n");
            }
            bpmnXml.append("  </process>\n");

            // BPMN Diagram (DI) section for layout information
            String diagramId = "BPMNDiagram_" + processId;
            String planeId = "BPMNPlane_" + processId;
            bpmnXml.append("  <bpmndi:BPMNDiagram id=\"").append(diagramId).append("\">\n");
            bpmnXml.append("    <bpmndi:BPMNPlane id=\"").append(planeId)
                    .append("\" bpmnElement=\"").append(processId).append("\">\n");
            // BPMNShape elements for each shape (with bounds)
            for (ShapeInfo si : shapesList) {
                bpmnXml.append("      <bpmndi:BPMNShape id=\"BPMNShape_").append(si.bpmnId)
                        .append("\" bpmnElement=\"").append(si.bpmnId).append("\">\n");
                bpmnXml.append("        <dc:Bounds x=\"").append(si.x)
                        .append("\" y=\"").append(si.y)
                        .append("\" width=\"").append(si.width)
                        .append("\" height=\"").append(si.height).append("\"/>\n");
                bpmnXml.append("      </bpmndi:BPMNShape>\n");
            }
            // BPMNEdge elements for each sequence flow (with waypoints)
            for (ConnectorInfo ci : flowList) {
                ShapeInfo fromShape = shapeMap.get(ci.fromId);
                ShapeInfo toShape = shapeMap.get(ci.toId);
                if (fromShape == null || toShape == null) continue;
                bpmnXml.append("      <bpmndi:BPMNEdge id=\"BPMNEdge_").append(ci.bpmnId)
                        .append("\" bpmnElement=\"").append(ci.bpmnId).append("\">\n");
                // Calculate waypoints (straight or L-shaped)
                int sx = fromShape.x + fromShape.width / 2;
                int sy = fromShape.y + fromShape.height / 2;
                int tx = toShape.x + toShape.width / 2;
                int ty = toShape.y + toShape.height / 2;
                if (sx == tx || sy == ty) {
                    // Straight line (horizontal or vertical)
                    bpmnXml.append("        <di:waypoint x=\"").append(sx).append("\" y=\"").append(sy).append("\"/>\n");
                    bpmnXml.append("        <di:waypoint x=\"").append(tx).append("\" y=\"").append(ty).append("\"/>\n");
                } else {
                    // L-shaped connector: horizontal then vertical
                    bpmnXml.append("        <di:waypoint x=\"").append(sx).append("\" y=\"").append(sy).append("\"/>\n");
                    bpmnXml.append("        <di:waypoint x=\"").append(tx).append("\" y=\"").append(sy).append("\"/>\n");
                    bpmnXml.append("        <di:waypoint x=\"").append(tx).append("\" y=\"").append(ty).append("\"/>\n");
                }
                bpmnXml.append("      </bpmndi:BPMNEdge>\n");
            }
            bpmnXml.append("    </bpmndi:BPMNPlane>\n");
            bpmnXml.append("  </bpmndi:BPMNDiagram>\n");
            bpmnXml.append("</definitions>\n");

            // Write the BPMN XML to the output file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
                writer.write(bpmnXml.toString());
            }
            System.out.println("BPMN XML successfully generated to " + outputPath);

        } catch (Exception e) {
            System.err.println("Error processing file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static double parseDoubleSafe(String value, double fallback) {
        if (value == null) return fallback;
        value = value.trim();
        if (value.isEmpty()) return fallback;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
