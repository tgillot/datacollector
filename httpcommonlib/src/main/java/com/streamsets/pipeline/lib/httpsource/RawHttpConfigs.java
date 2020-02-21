/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.lib.httpsource;

import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.credential.CredentialValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RawHttpConfigs extends CommonHttpConfigs {

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.CREDENTIAL,
      label = "Application ID",
      description = "Only HTTP request presenting this token will be accepted",
      displayPosition = 20,
      group = "HTTP"
  )
  public CredentialValue appId = () -> "";

  @Override
  public List<? extends CredentialValue> getAppIds() {
    return new ArrayList<>(Arrays.asList(appId));
  }

  @Override
  public boolean isApplicationIdEnabled(){
    return true;
  }

}
