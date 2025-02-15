package com.symphony.bdk.workflow.engine.camunda;

import com.symphony.bdk.workflow.engine.ResourceProvider;
import com.symphony.bdk.workflow.engine.executor.ActivityExecutor;
import com.symphony.bdk.workflow.engine.executor.ActivityExecutorContext;
import com.symphony.bdk.workflow.engine.executor.BdkGateway;
import com.symphony.bdk.workflow.engine.executor.EventHolder;
import com.symphony.bdk.workflow.swadl.v1.activity.BaseActivity;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.Variables;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class CamundaExecutor implements JavaDelegate {

  public static final String EXECUTOR = "executor";
  public static final String ACTIVITY = "activity";

  public static final ObjectMapper OBJECT_MAPPER;

  // set MDC entries so that executors can produce log that we can contextualize
  private static final String MDC_PROCESS_ID = "PROCESS_ID";
  private static final String MDC_ACTIVITY_ID = "ACTIVITY_ID";

  static {
    OBJECT_MAPPER = JsonMapper.builder()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        // to escape # or $ in message received content and still serialize it to JSON
        .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        .build();
  }

  private final BdkGateway bdk;
  private final AuditTrailLogger auditTrailLogger;
  private final ResourceProvider resourceLoader;

  public CamundaExecutor(BdkGateway bdk, AuditTrailLogger auditTrailLogger,
      @Qualifier("workflowResourcesProvider") ResourceProvider resourceLoader) {
    this.bdk = bdk;
    this.auditTrailLogger = auditTrailLogger;
    this.resourceLoader = resourceLoader;
  }

  @Override
  public void execute(DelegateExecution execution) throws Exception {
    Class<?> implClass = Class.forName((String) execution.getVariable(EXECUTOR));

    ActivityExecutor<?> executor = (ActivityExecutor<?>) implClass.getDeclaredConstructor().newInstance();

    Type type =
        ((ParameterizedType) (implClass.getGenericInterfaces()[0])).getActualTypeArguments()[0];

    String activityAsJsonString = (String) execution.getVariable(ACTIVITY);
    Object activity = OBJECT_MAPPER.readValue(activityAsJsonString, Class.forName(type.getTypeName()));

    EventHolder event = (EventHolder) execution.getVariable(ActivityExecutorContext.EVENT);

    try {
      setMdc(execution);
      auditTrailLogger.execute(execution, activity.getClass().getSimpleName());
      executor.execute(new CamundaActivityExecutorContext(execution, (BaseActivity) activity, event,
          resourceLoader, bdk));
    } finally {
      clearMdc();
    }
  }

  private void setMdc(DelegateExecution execution) {
    MDC.put(MDC_PROCESS_ID, execution.getProcessInstanceId());
    MDC.put(MDC_ACTIVITY_ID, execution.getActivityInstanceId());
  }

  private void clearMdc() {
    MDC.remove(MDC_PROCESS_ID);
    MDC.remove(MDC_ACTIVITY_ID);
  }

  private static class CamundaActivityExecutorContext<T extends BaseActivity> implements ActivityExecutorContext<T> {
    private final DelegateExecution execution;
    private final T activity;
    private final EventHolder<Object> event;
    private final ResourceProvider resourceLoader;
    private final BdkGateway bdk;

    public CamundaActivityExecutorContext(DelegateExecution execution, T activity, EventHolder<Object> event,
        ResourceProvider resourceLoader, BdkGateway bdk) {
      this.execution = execution;
      this.activity = activity;
      this.event = event;
      this.resourceLoader = resourceLoader;
      this.bdk = bdk;
    }

    @Override
    public void setOutputVariable(String name, Object value) {
      Map<String, Object> innerMap = Collections.singletonMap(name, value);
      Map<String, Object> outerMap = Collections.singletonMap(ActivityExecutorContext.OUTPUTS, innerMap);
      String activityId = getActivity().getId();

      // value might not implement serializable or be a collection with non-serializable items, so we use JSON if needed
      Object outerMapVar;
      Object valueVar;
      if (value instanceof Serializable && !(value instanceof Collection)) {
        outerMapVar = outerMap;
        valueVar = value;
      } else {
        outerMapVar =
            Variables.objectValue(outerMap).serializationDataFormat(Variables.SerializationDataFormats.JSON).create();
        valueVar =
            Variables.objectValue(value).serializationDataFormat(Variables.SerializationDataFormats.JSON).create();
      }

      execution.setVariable(activityId, outerMapVar);
      // flatten it too for message correlation
      execution.setVariable(String.format("%s.%s.%s", activityId, ActivityExecutorContext.OUTPUTS, name), valueVar);
    }

    @Override
    public BdkGateway bdk() {
      return bdk;
    }

    @Override
    public T getActivity() {
      return activity;
    }

    @Override
    public EventHolder<Object> getEvent() {
      return event;
    }

    @Override
    public InputStream getResource(String resourcePath) throws IOException {
      return resourceLoader.getResource(resourcePath);
    }
  }
}
