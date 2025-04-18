package org.camunda.bpmn.generator;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.flowable.bpmn.model.Process;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class BPMNGenFromPegaFlowable {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Two arguments are required: input file and output file.");
        }

        args = new String[]{"SamplePegaProcess.xml", "ConverterFlowable.bpmn20.xml"};

        File file = new File(args[0]);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(file);
        doc.getDocumentElement().normalize();

        XPath xpath = XPathFactory.newInstance().newXPath();

        BpmnModel bpmnModel = new BpmnModel();
        Process process = new Process();
        process.setId("convertedProcess");
        process.setName("Pega Converted Process");
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        Map<String, String> idMap = new HashMap<>();
        Map<String, FlowElement> elementMap = new HashMap<>();
        double x = 100;
        double y = 100;
        double spacing = 200;
        int index = 0;

        index = addNodes(process, bpmnModel, doc, xpath, "Data-MO-Event-Start", StartEvent::new, "startEvent", x, y, index, spacing, idMap, elementMap);
        index = addNodes(process, bpmnModel, doc, xpath, "Data-MO-Activity-Assignment", UserTask::new, "userTask", x, y, index, spacing, idMap, elementMap);
        index = addNodes(process, bpmnModel, doc, xpath, "Data-MO-Activity-SubProcess", SubProcess::new, "subProcess", x, y, index, spacing, idMap, elementMap);
        index = addNodes(process, bpmnModel, doc, xpath, "Data-MO-Activity-Utility", ServiceTask::new, "serviceTask", x, y, index, spacing, idMap, elementMap);
        index = addNodes(process, bpmnModel, doc, xpath, "Data-MO-Gateway-Decision", ExclusiveGateway::new, "gateway", x, y, index, spacing, idMap, elementMap);
        index = addNodes(process, bpmnModel, doc, xpath, "Data-MO-Event-End", EndEvent::new, "endEvent", x, y, index, spacing, idMap, elementMap);

        XPathExpression seqExpr = xpath.compile("//*[pxObjClass='Data-MO-Connector-Transition']");
        NodeList sequenceList = (NodeList) seqExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < sequenceList.getLength(); i++) {
            Node parent = sequenceList.item(i);
            String fromId = getNodeValue(parent, xpath, "pyFrom");
            String toId = getNodeValue(parent, xpath, "pyTo");

            String sourceRef = idMap.get(fromId);
            String targetRef = idMap.get(toId);

            if (sourceRef == null || targetRef == null) {
                System.out.println("Skipping sequence flow due to missing source/target: " + fromId + " -> " + toId);
                continue;
            }

            SequenceFlow sf = new SequenceFlow();
            sf.setId("flow" + i);
            sf.setSourceRef(sourceRef);
            sf.setTargetRef(targetRef);
            sf.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(sf);

            GraphicInfo src = bpmnModel.getGraphicInfo(sourceRef);
            GraphicInfo tgt = bpmnModel.getGraphicInfo(targetRef);
            if (src != null && tgt != null) {
                List<GraphicInfo> line = new ArrayList<>();
                GraphicInfo p1 = new GraphicInfo();
                p1.setX(src.getX() + 50);
                p1.setY(src.getY() + 25);
                line.add(p1);
                GraphicInfo p2 = new GraphicInfo();
                p2.setX(tgt.getX());
                p2.setY(tgt.getY() + 25);
                line.add(p2);
                bpmnModel.addFlowGraphicInfoList(sf.getId(), line);
            }
        }

        BpmnXMLConverter converter = new BpmnXMLConverter();
        byte[] xmlBytes = converter.convertToXML(bpmnModel);
        Files.write(Paths.get(args[1]), xmlBytes);
        System.out.println("✅ BPMN diagram created at: " + args[1]);
    }

    private static int addNodes(Process process, BpmnModel model, Document doc, XPath xpath, String pegaType,
                                ElementFactory factory, String prefix, double startX, double startY, int index, double spacing,
                                Map<String, String> idMap, Map<String, FlowElement> elementMap) throws Exception {
        XPathExpression expr = xpath.compile("//*[pyShapeType='" + pegaType + "']");
        NodeList list = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);

        int rowSize = 5;
        for (int i = 0; i < list.getLength(); i++) {
            Node parent = list.item(i);
            FlowElement element = factory.create();
            String id = prefix + index;
            element.setId(id);
            element.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(element);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), id);
            elementMap.put(id, element);

            int row = index / rowSize;
            int col = index % rowSize;

            GraphicInfo gi = new GraphicInfo();
            gi.setX(startX + col * spacing);
            gi.setY(startY + row * spacing);
            gi.setWidth(100);
            gi.setHeight(50);
            model.addGraphicInfo(id, gi);
            index++;
        }
        return index;
    }

    private static String getNodeValue(Node parent, XPath xpath, String tag) throws XPathExpressionException {
        XPathExpression expr = xpath.compile(tag);
        NodeList list = (NodeList) expr.evaluate(parent, XPathConstants.NODESET);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return "";
    }

    interface ElementFactory {
        FlowElement create();
    }
}
