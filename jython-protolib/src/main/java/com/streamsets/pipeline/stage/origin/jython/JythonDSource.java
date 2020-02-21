/*
 * Copyright 2019 StreamSets Inc.
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
package com.streamsets.pipeline.stage.origin.jython;

import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.PushSource;
import com.streamsets.pipeline.api.StageBehaviorFlags;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.stage.origin.scripting.AbstractScriptingDSource;
import com.streamsets.pipeline.stage.origin.scripting.config.Groups;

@GenerateResourceBundle
@StageDef(
    version = 1,
    label = "Jython Scripting",
    description = "Produces record batches using Jython script",
    execution = {ExecutionMode.STANDALONE},
    icon = "jython.png",
    producesEvents = true,
    flags = StageBehaviorFlags.USER_CODE_INJECTION,
    onlineHelpRefUrl = "index.html?contextID=task_ptn_xnj_l3b"
)
@ConfigGroups(value = Groups.class)

public class JythonDSource extends AbstractScriptingDSource {

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.TEXT,
      defaultValueFromResource = "GeneratorOriginScript.py",
      label = "User Script",
      description = "Press F11 (or ESC on Mac OS X) when cursor is in the editor to "
        + "toggle full screen editing.",
      displayPosition = 10,
      group = "SCRIPT",
      mode = ConfigDef.Mode.PYTHON)
  public String script;

  @Override
  protected PushSource createPushSource() {
    return new JythonSource(script, scriptConf);
  }

}
