package com.symphony.bdk.workflow.swadl.v1.activity.stream;

import com.symphony.bdk.workflow.swadl.v1.activity.BaseActivity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.annotation.Nullable;

/**
 * @see <a href="https://developers.symphony.com/restapi/reference#stream-members">Get stream members API</a>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class GetStreamMembers extends BaseActivity {
  private String streamId;
  @Nullable private String limit;
  @Nullable private String skip;

  @JsonIgnore
  public Integer getLimitAsInt() {
    return toInt(limit);
  }

  @JsonIgnore
  public Integer getSkipAsInt() {
    return toInt(skip);
  }
}
