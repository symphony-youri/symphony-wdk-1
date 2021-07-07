package com.symphony.bdk.workflow.engine.camunda.bpmn;

import com.symphony.bdk.workflow.engine.camunda.activity.Reply;
import com.symphony.bdk.workflow.lang.swadl.Activity;
import com.symphony.bdk.workflow.lang.swadl.Event;
import com.symphony.bdk.workflow.lang.swadl.Workflow;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.camunda.bpm.model.bpmn.builder.ProcessBuilder;
import org.camunda.bpm.model.xml.ModelValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class CamundaBpmnBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(CamundaBpmnBuilder.class);

  private static final String OUTPUT_BPMN_FILE_NAME = "build/resources/main/output.bpmn";

  private final RepositoryService repositoryService;

  // run a single workflow at anytime
  private Deployment deploy;

  @Autowired
  public CamundaBpmnBuilder(RepositoryService repositoryService) {
    this.repositoryService = repositoryService;
  }

  public void generateBpmnOutputFile(Workflow workflow) throws IOException {
    BpmnModelInstance instance = workflowToBpmn(workflow);

    File file = new File(OUTPUT_BPMN_FILE_NAME);
    if (file.exists()) {
      LOGGER.info("Output bpmn file {} already exists. It will be overridden.", OUTPUT_BPMN_FILE_NAME);
    } else {
      boolean successfullyCreated = file.createNewFile();
      String logMessage = successfullyCreated
          ? String.format("Output bpmn file %s is created.", OUTPUT_BPMN_FILE_NAME)
          : String.format("Output bpmn file %s is NOT created.", OUTPUT_BPMN_FILE_NAME);
      LOGGER.info(logMessage);
    }

    try {
      Bpmn.writeModelToFile(file, instance);
      LOGGER.info("Output bpmn file {} is updated.", OUTPUT_BPMN_FILE_NAME);
    } catch (BpmnModelException | ModelValidationException e) {
      LOGGER.error(e.getMessage());
    }
  }

  public void addWorkflow(Workflow workflow) {
    BpmnModelInstance instance = workflowToBpmn(workflow);

    if (deploy != null) {
      repositoryService.deleteDeployment(deploy.getId());
    }
    deploy = repositoryService.createDeployment()
        .addModelInstance(workflow.getName() + ".bpmn", instance)
        .deploy();
  }


  private String getCommandToStart(Workflow workflow) {
    Optional<LinkedHashMap<String, Activity>> first =
        workflow.getActivities().stream().findFirst();

    if (!first.isPresent()) {
      return null;
    }

    Optional<Activity> activity = first.get().values().stream().findFirst();
    if (!activity.isPresent()) {
      return null;
    }

    Event on = activity.get().getOn();

    if (on != null && on.getContent() != null) {
      return on.getContent().get("content");
    } else if (on != null && on.getStreamId() != null) {
      return on.getStreamId().get("streamId");
    } else {
      return null;
    }
  }

  private LinkedHashMap<String, Activity> activitiesToList(Workflow workflow) {
    LinkedHashMap<String, Activity> activities = new LinkedHashMap<>();

    for (LinkedHashMap<String, Activity> activity : workflow.getActivities()) {
      Optional<Activity> first = activity.values().stream().findFirst();

      // To avoid overriding any existing entry with the same name, we add a random suffix
      String uuid = "-".concat(UUID.randomUUID().toString().substring(0, 2));
      first.ifPresent(
          value -> activities.put(
              activity.keySet().stream().findFirst().get() + uuid, value));
    }

    return activities;
  }

  private BpmnModelInstance workflowToBpmn(Workflow workflow) {
    ProcessBuilder process = Bpmn.createExecutableProcess(workflow.getName());

    String commandToStart = getCommandToStart(workflow);
    AbstractFlowNodeBuilder eventBuilder = process.startEvent().message("message_" + commandToStart);

    Map<String, Activity> activitiesMap = this.activitiesToList(workflow);
    this.mockReplyOnActivities(activitiesMap);

    for (Map.Entry<String, Activity> entry : activitiesMap.entrySet()) {
      if (entry.getValue().getReply() != null) {
        eventBuilder = eventBuilder.serviceTask()
            .camundaClass(Reply.class)
            .name(entry.getKey())
            .camundaInputParameter("messageML", entry.getValue().getReply());
      }
    }

    return eventBuilder.endEvent().done();
  }

  /**
   * For the time being, the workflow definition doesn't take in inputs activities reply.
   * This method inserts a basic custom reply message for demo purposes.
   */
  /*TODO Activity reply should be passed in the workflow definition file, or predefined for each type*/
  private void mockReplyOnActivities(Map<String, Activity> activities) {
    String reply = "<messageML> current activity description: %s </messageML>";

    for (Map.Entry<String, Activity> entry : activities.entrySet()) {
      String customReply = String.format(reply, entry.getValue().getDescription());
      entry.getValue().setReply(customReply);
    }
  }

}