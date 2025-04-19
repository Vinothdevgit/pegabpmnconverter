package com.example.converter;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.flowable.bpmn.model.Process;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class PegaToFlowableConverter {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java PegaToFlowableConverter <inputFile> <outputFile>");
          //  return;
        }
        args = new String[]{"SamplePegaProcess.xml", "ConverterFlowable.bpmn20.xml"};
        String pegaXmlFile = args[0];
        String outputBpmnFile = args[1];

        DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document pegaDoc = docBuilder.parse(new File(pegaXmlFile));

        BpmnModel bpmnModel = new BpmnModel();
        Process process = new Process();
        process.setId("pegaConvertedProcess");
        process.setName("Pega Converted Process");
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        Map<String, FlowElement> elementMap = new HashMap<>();

        NodeList assignments = pegaDoc.getElementsByTagName("Assignment");
        for (int i = 0; i < assignments.getLength(); i++) {
            Element assignEl = (Element) assignments.item(i);
            String pegaId = assignEl.getAttribute("id");
            String name = assignEl.getAttribute("name");
            UserTask userTask = new UserTask();
            userTask.setId("task_" + pegaId);
            userTask.setName(name);
            String assignedTo = assignEl.getAttribute("assignedOperator");
            if (assignedTo != null && !assignedTo.isEmpty()) {
                userTask.setAssignee(assignedTo);
            }
            process.addFlowElement(userTask);
            elementMap.put(pegaId, userTask);
        }

        NodeList utilities = pegaDoc.getElementsByTagName("Utility");
        for (int i = 0; i < utilities.getLength(); i++) {
            Element utilEl = (Element) utilities.item(i);
            String pegaId = utilEl.getAttribute("id");
            String name = utilEl.getAttribute("name");
            ServiceTask serviceTask = new ServiceTask();
            serviceTask.setId("service_" + pegaId);
            serviceTask.setName(name);
            String activity = utilEl.getAttribute("activityName");
            if (activity != null && !activity.isEmpty()) {
                serviceTask.setDocumentation("Originally called Pega activity: " + activity);
            }
            process.addFlowElement(serviceTask);
            elementMap.put(pegaId, serviceTask);
        }

        NodeList decisions = pegaDoc.getElementsByTagName("Decision");
        for (int i = 0; i < decisions.getLength(); i++) {
            Element decEl = (Element) decisions.item(i);
            String pegaId = decEl.getAttribute("id");
            String name = decEl.getAttribute("name");
            ExclusiveGateway gateway = new ExclusiveGateway();
            gateway.setId("gateway_" + pegaId);
            gateway.setName(name);
            process.addFlowElement(gateway);
            elementMap.put(pegaId, gateway);
        }

        NodeList connectors = pegaDoc.getElementsByTagName("Connector");
        for (int i = 0; i < connectors.getLength(); i++) {
            Element connEl = (Element) connectors.item(i);
            String fromId = connEl.getAttribute("sourceShape");
            String toId = connEl.getAttribute("targetShape");
            String cond = connEl.getAttribute("when");
            String label = connEl.getAttribute("label");

            FlowElement sourceElem = elementMap.get(fromId);
            FlowElement targetElem = elementMap.get(toId);
            if (sourceElem == null || targetElem == null) {
                System.err.println("Warning: Connector from " + fromId + " to " + toId + " cannot be resolved. Skipping.");
                continue;
            }

            SequenceFlow flow = new SequenceFlow(sourceElem.getId(), targetElem.getId());
            String flowId = "flow_" + fromId + "_to_" + toId;
            flow.setId(flowId);
            if (label != null && !label.isEmpty()) {
                flow.setName(label);
            }
            if (cond != null && !cond.isEmpty()) {
                flow.setConditionExpression("${" + cond + "}");
            }
            process.addFlowElement(flow);
        }

        if (pegaDoc.getElementsByTagName("Start").getLength() == 0 && !elementMap.isEmpty()) {
            StartEvent startEvent = new StartEvent();
            startEvent.setId("startEvent1");
            startEvent.setName("Start");
            process.addFlowElement(startEvent);
            FlowElement firstTask = elementMap.values().iterator().next();
            SequenceFlow startFlow = new SequenceFlow(startEvent.getId(), firstTask.getId());
            startFlow.setId("flow_start_to_" + firstTask.getId());
            process.addFlowElement(startFlow);
        }

        BpmnXMLConverter bpmnConverter = new BpmnXMLConverter();
        byte[] bpmnBytes = bpmnConverter.convertToXML(bpmnModel);
        Files.write(Paths.get(outputBpmnFile), bpmnBytes);
        System.out.println("Conversion complete. BPMN XML saved to " + outputBpmnFile);
    }
}