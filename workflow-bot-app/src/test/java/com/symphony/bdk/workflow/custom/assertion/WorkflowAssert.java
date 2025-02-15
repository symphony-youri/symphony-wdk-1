package com.symphony.bdk.workflow.custom.assertion;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;

import com.symphony.bdk.core.service.message.model.Attachment;
import com.symphony.bdk.core.service.message.model.Message;
import com.symphony.bdk.workflow.IntegrationTest;
import com.symphony.bdk.workflow.swadl.v1.Activity;
import com.symphony.bdk.workflow.swadl.v1.Workflow;
import com.symphony.bdk.workflow.swadl.v1.activity.BaseActivity;

import org.apache.commons.io.IOUtils;
import org.assertj.core.api.AbstractAssert;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricDetail;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricDetailVariableInstanceUpdateEntity;
import org.mockito.ArgumentMatcher;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class WorkflowAssert extends AbstractAssert<WorkflowAssert, Workflow> {
  public WorkflowAssert(Workflow workflow) {
    super(workflow, WorkflowAssert.class);
  }

  public static WorkflowAssert assertThat(Workflow actual) {
    return new WorkflowAssert(actual);
  }

  public static void assertMessage(Message actual, Message expected) throws IOException {
    org.assertj.core.api.Assertions.assertThat(actual.getContent())
        .as("The same content should be found")
        .isEqualTo(expected.getContent());
    org.assertj.core.api.Assertions.assertThat(actual.getData())
        .as("The same data should be found")
        .isEqualTo(expected.getData());
    assertAttachments(actual.getAttachments(), expected.getAttachments());
  }

  public static Message content(final String content) {
    return argThat(new ArgumentMatcher<>() {
      @Override
      public boolean matches(Message argument) {
        return argument.getContent().equals("<messageML>" + content + "</messageML>");
      }

      @Override
      public String toString() {
        return "messageWithContent(" + content + ")";
      }
    });
  }

  public static Message contains(final String content) {
    return argThat(new ArgumentMatcher<>() {
      @Override
      public boolean matches(Message argument) {
        return argument.getContent().contains(content);
      }

      @Override
      public String toString() {
        return "messageContainingContent(" + content + ")";
      }
    });
  }

  public WorkflowAssert isExecuted() {
    isNotNull();
    assertExecuted(actual);
    return this;
  }

  public WorkflowAssert isExecutedWithProcessAndActivities(Optional<String> process, List<String> activities) {
    isNotNull();
    assertExecuted(process, activities);
    return this;
  }

  public WorkflowAssert hasOutput(String key, Object value) {
    isNotNull();
    this.assertOutputs(key, value);
    return this;
  }

  public static Optional<String> lastProcess(Workflow workflow) {
    List<HistoricProcessInstance> processes = IntegrationTest.historyService.createHistoricProcessInstanceQuery()
        .processDefinitionName(workflow.getId())
        .orderByProcessInstanceStartTime().desc()
        .list();
    if (processes.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.ofNullable(processes.get(0))
          .map(HistoricProcessInstance::getId);
    }
  }

  public static Optional<String> lastProcess() {
    List<HistoricProcessInstance> processes = IntegrationTest.historyService.createHistoricProcessInstanceQuery()
        .orderByProcessInstanceStartTime().desc()
        .list();
    if (processes.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.ofNullable(processes.get(0))
          .map(HistoricProcessInstance::getId);
    }
  }

  public static Boolean processIsCompleted(String processId) {
    List<HistoricProcessInstance> processes = IntegrationTest.historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(processId).list();
    if (!processes.isEmpty()) {
      HistoricProcessInstance processInstance = processes.get(0);
      return processInstance.getState().equals("COMPLETED");
    }
    return false;
  }

  public static void assertExecuted(Workflow workflow) {
    String[] activityIds = workflow.getActivities().stream()
        .map(Activity::getActivity)
        .map(BaseActivity::getId)
        .toArray(String[]::new);
    assertExecuted(activityIds);
  }

  public static void assertExecuted(Optional<String> process, List<String> activities) {
    org.assertj.core.api.Assertions.assertThat(process).hasValueSatisfying(
        processId -> await().atMost(5, SECONDS).until(() -> processIsCompleted(processId)));

    List<HistoricActivityInstance> processes =
        IntegrationTest.historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(process.get())
            .activityType("scriptTask")
            .orderByHistoricActivityInstanceStartTime().asc()
            .orderByActivityName().asc()
            .list();

    org.assertj.core.api.Assertions.assertThat(processes)
        .extracting(HistoricActivityInstance::getActivityName)
        .containsExactly(activities.toArray(String[]::new));
  }

  private static void assertExecuted(String... activityIds) {
    String process = lastProcess().orElseThrow();
    await().atMost(5, SECONDS).until(() -> processIsCompleted(process));

    List<HistoricActivityInstance> processes =
        IntegrationTest.historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(process)
            .orderByHistoricActivityInstanceStartTime().asc()
            .orderByActivityName().asc()
            .list();

    org.assertj.core.api.Assertions.assertThat(processes)
        .filteredOn(p -> !p.getActivityType().equals("signalStartEvent"))
        .extracting(HistoricActivityInstance::getActivityName)
        .containsExactly(activityIds);
  }


  private void assertOutputs(String key, Object value) {
    String process = lastProcess().orElseThrow();
    await().atMost(5, SECONDS).until(() -> processIsCompleted(process));

    final List<HistoricDetail> historicalDetails =
        IntegrationTest.historyService.createHistoricDetailQuery().processInstanceId(process).list();

    Optional<HistoricDetail> historicalDetailOptional = historicalDetails.stream()
        .filter(x -> ((HistoricDetailVariableInstanceUpdateEntity) x).getVariableName().equals(key))
        .findFirst();

    if (historicalDetailOptional.isEmpty()) {
      fail("No historical details found for the process.");
    } else {
      HistoricDetail historicalDetail = historicalDetailOptional.get();
      String actualVariableName = ((HistoricDetailVariableInstanceUpdateEntity) historicalDetail).getVariableName();
      Object actualVariableValue = ((HistoricDetailVariableInstanceUpdateEntity) historicalDetail).getValue();

      if (!actualVariableName.equals(key)) {
        failWithMessage("Expected variable key to be %s but was %s", key, actualVariableName);
      }

      if ((actualVariableValue == null || value == null) && actualVariableValue != value) {
        fail("Expected variable value to be %s but was %s", value, actualVariableValue);
      } else if (actualVariableValue != null && !actualVariableValue.equals(value)) {
        failWithMessage("Actual variable value was different to the expected one");
      }
    }
  }

  private static void assertAttachments(List<Attachment> actual, List<Attachment> expected) throws IOException {
    if (actual == null || actual.isEmpty() || expected == null || expected.isEmpty()) {
      org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    } else {
      org.assertj.core.api.Assertions.assertThat(actual.size()).isEqualTo(expected.size());
      for (Attachment actualAttachment : actual) {
        boolean isFound = false;
        for (Attachment expectedAttachment : expected) {

          // reset both InputStreams to the initial state,
          // otherwise the equality check will return false even if they have the same values in the byte array
          actualAttachment.getContent().reset();
          expectedAttachment.getContent().reset();

          if (IOUtils.contentEquals(expectedAttachment.getContent(), actualAttachment.getContent())
              && expectedAttachment.getFilename().equals(actualAttachment.getFilename())) {
            isFound = true;
            break;
          }
        }
        org.assertj.core.api.Assertions.assertThat(isFound).isTrue();
      }
    }
  }

}
