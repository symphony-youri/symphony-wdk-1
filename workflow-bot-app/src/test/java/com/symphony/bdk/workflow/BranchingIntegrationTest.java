package com.symphony.bdk.workflow;

import static com.symphony.bdk.workflow.custom.assertion.WorkflowAssert.assertThat;
import static com.symphony.bdk.workflow.custom.assertion.WorkflowAssert.lastProcess;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.symphony.bdk.workflow.swadl.SwadlParser;
import com.symphony.bdk.workflow.swadl.v1.Workflow;

import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

class BranchingIntegrationTest extends IntegrationTest {

  static Stream<Arguments> executedActivities() {
    return Stream.of(
        arguments("/branching/if.swadl.yaml", List.of("act1", "act2")),
        arguments("/branching/if-else-end.swadl.yaml", List.of("act1")),
        arguments("/branching/if-else-activity.swadl.yaml", List.of("act1", "act3")),
        arguments("/branching/if-else-if.swadl.yaml", List.of("act1", "act3")),
        arguments("/branching/if-nested.swadl.yaml", List.of("act1", "act2", "act2_2")),
        arguments("/branching/if-else-nested.swadl.yaml", List.of("act1", "act2", "act2_3")),
        arguments("/branching/if-join.swadl.yaml", List.of("act1", "act2", "act4")),
        arguments("/branching/second-if-join.swadl.yaml", List.of("act1", "act3", "act4")),
        arguments("/branching/if-else-join.swadl.yaml", List.of("act1", "act3", "act4")),
        arguments("/branching/if-join-continue.swadl.yaml", List.of("act1", "act2", "act4", "act5")),
        arguments("/branching/if-else-more-activities.swadl.yaml", List.of("act1", "act3", "act3_2"))
    );
  }

  @ParameterizedTest
  @MethodSource("executedActivities")
  void branching(String workflowFile, List<String> activities) throws IOException, ProcessingException {
    final Workflow workflow = SwadlParser.fromYaml(getClass().getResourceAsStream(workflowFile));
    engine.execute(workflow);

    engine.onEvent(messageReceived("/execute"));

    assertThat(workflow).isExecutedWithProcessAndActivities(lastProcess(), activities);
  }

}