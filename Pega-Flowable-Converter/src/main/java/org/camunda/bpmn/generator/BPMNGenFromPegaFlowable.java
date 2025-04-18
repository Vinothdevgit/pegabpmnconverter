package org.camunda.bpmn.generator;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

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
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("convertedProcess");
        process.setName("Pega Converted Process");
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        HashMap<String, String> idMap = new HashMap<>();

        // Start Events
        XPathExpression startExpr = xpath.compile("//*[pyShapeType='Data-MO-Event-Start']");
        NodeList startList = (NodeList) startExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < startList.getLength(); i++) {
            Node parent = startList.item(i);
            StartEvent startEvent = new StartEvent();
            startEvent.setId("startEvent" + i);
            startEvent.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(startEvent);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), startEvent.getId());
        }

        // User Tasks
        XPathExpression taskExpr = xpath.compile("//*[pyShapeType='Data-MO-Activity-Assignment']");
        NodeList taskList = (NodeList) taskExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < taskList.getLength(); i++) {
            Node parent = taskList.item(i);
            UserTask userTask = new UserTask();
            userTask.setId("userTask" + i);
            userTask.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(userTask);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), userTask.getId());
        }

        // SubProcesses
        XPathExpression subExpr = xpath.compile("//*[pyShapeType='Data-MO-Activity-SubProcess']");
        NodeList subList = (NodeList) subExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < subList.getLength(); i++) {
            Node parent = subList.item(i);
            SubProcess subProcess = new SubProcess();
            subProcess.setId("subProcess" + i);
            subProcess.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(subProcess);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), subProcess.getId());
        }

        // Service Tasks
        XPathExpression serviceExpr = xpath.compile("//*[pyShapeType='Data-MO-Activity-Utility']");
        NodeList serviceList = (NodeList) serviceExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < serviceList.getLength(); i++) {
            Node parent = serviceList.item(i);
            ServiceTask serviceTask = new ServiceTask();
            serviceTask.setId("serviceTask" + i);
            serviceTask.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(serviceTask);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), serviceTask.getId());
        }

        // Exclusive Gateways
        XPathExpression gatewayExpr = xpath.compile("//*[pyShapeType='Data-MO-Gateway-Decision']");
        NodeList gatewayList = (NodeList) gatewayExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < gatewayList.getLength(); i++) {
            Node parent = gatewayList.item(i);
            ExclusiveGateway gateway = new ExclusiveGateway();
            gateway.setId("gateway" + i);
            gateway.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(gateway);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), gateway.getId());
        }

        // End Events
        XPathExpression endExpr = xpath.compile("//*[pyShapeType='Data-MO-Event-End']");
        NodeList endList = (NodeList) endExpr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < endList.getLength(); i++) {
            Node parent = endList.item(i);
            EndEvent endEvent = new EndEvent();
            endEvent.setId("endEvent" + i);
            endEvent.setName(getNodeValue(parent, xpath, "pyMOName"));
            process.addFlowElement(endEvent);
            idMap.put(getNodeValue(parent, xpath, "pyMOId"), endEvent.getId());
        }

        // Sequence Flows
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
        }

        // Write BPMN to file
        BpmnXMLConverter converter = new BpmnXMLConverter();
        byte[] xmlBytes = converter.convertToXML(bpmnModel);
        Files.write(Paths.get(args[1]), xmlBytes);
        System.out.println("✅ Successfully converted Pega BPMN to Flowable XML: " + args[1]);
    }

    private static String getNodeValue(Node parent, XPath xpath, String tag) throws XPathExpressionException {
        XPathExpression expr = xpath.compile(tag);
        NodeList list = (NodeList) expr.evaluate(parent, XPathConstants.NODESET);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return "";
    }
}
