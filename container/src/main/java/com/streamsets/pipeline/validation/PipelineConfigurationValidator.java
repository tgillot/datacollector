/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.validation;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.streamsets.pipeline.config.ConfigConfiguration;
import com.streamsets.pipeline.config.ConfigDefinition;
import com.streamsets.pipeline.config.PipelineConfiguration;
import com.streamsets.pipeline.config.StageConfiguration;
import com.streamsets.pipeline.config.StageDefinition;
import com.streamsets.pipeline.config.StageType;
import com.streamsets.pipeline.api.impl.TextUtils;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PipelineConfigurationValidator {
  private static final Logger LOG = LoggerFactory.getLogger(PipelineConfigurationValidator.class);

  private final StageLibraryTask stageLibrary;
  private final String name;
  private final PipelineConfiguration pipelineConfiguration;
  private final Issues issues;
  private final List<String> openLanes;
  private boolean validated;
  private boolean canPreview;

  public PipelineConfigurationValidator(StageLibraryTask stageLibrary, String name, PipelineConfiguration pipelineConfiguration) {
    Preconditions.checkNotNull(stageLibrary, "stageLibrary cannot be null");
    Preconditions.checkNotNull(name, "name cannot be null");
    Preconditions.checkNotNull(pipelineConfiguration, "pipelineConfiguration cannot be null");
    this.stageLibrary = stageLibrary;
    this.name = name;
    this.pipelineConfiguration = pipelineConfiguration;
    issues = new Issues();
    openLanes = new ArrayList<>();
  }

  boolean sortStages() {
    boolean ok = true;
    List<StageConfiguration> original = new ArrayList<>(pipelineConfiguration.getStages());
    List<StageConfiguration> sorted = new ArrayList<>();
    Set<String> producedOutputs = new HashSet<>();
    while (ok && !original.isEmpty()) {
      int prior = sorted.size();
      Iterator<StageConfiguration> it = original.iterator();
      while (it.hasNext()) {
        StageConfiguration stage = it.next();
        if (producedOutputs.containsAll(stage.getInputLanes())) {
          producedOutputs.addAll(stage.getOutputLanes());
          it.remove();
          sorted.add(stage);
        }
      }
      if (prior == sorted.size()) {
        // pipeline has not stages at all
        List<String> names = new ArrayList<>(original.size());
        for (StageConfiguration stage : original) {
          names.add(stage.getInstanceName());
        }
        issues.addP(new Issue(ValidationError.VALIDATION_0002, names));
        ok = false;
      }
    }
    sorted.addAll(original);
    pipelineConfiguration.setStages(sorted);
    return ok;
  }

  public boolean validate() {
    Preconditions.checkState(!validated, "Already validated");
    validated = true;
    LOG.trace("Pipeline '{}' starting validation", name);
    canPreview = sortStages();
    canPreview &= checkIfPipelineIsEmpty();
    canPreview &= validatePipelineConfiguration();
    canPreview &= validatePipelineLanes();
    if (LOG.isTraceEnabled() && issues.hasIssues()) {
      for (Issue issue : issues.getPipelineIssues()) {
        LOG.trace("Pipeline '{}', {}", name, issue);
      }
      for (List<StageIssue> stageIssues : issues.getStageIssues().values()) {
        for (StageIssue stageIssue  :stageIssues) {
          LOG.trace("Pipeline '{}', {}", name, stageIssue);
        }
      }
    }
    LOG.debug("Pipeline '{}' validation. valid={}, canPreview={}, issuesCount={}", name, !issues.hasIssues(),
              canPreview, issues.getIssueCount());
    return !issues.hasIssues();
  }

  public boolean canPreview() {
    Preconditions.checkState(validated, "validate() has not been called");
    return canPreview;
  }

  public Issues getIssues() {
    Preconditions.checkState(validated, "validate() has not been called");
    return issues;
  }

  public List<String> getOpenLanes() {
    Preconditions.checkState(validated, "validate() has not been called");
    return openLanes;
  }

  boolean checkIfPipelineIsEmpty() {
    boolean preview = true;
    if (pipelineConfiguration.getStages().isEmpty()) {
      // pipeline has not stages at all
      issues.addP(new Issue(ValidationError.VALIDATION_0001));
      preview = false;
    }
    return preview;
  }

  private ConfigConfiguration getConfig(List<ConfigConfiguration> configs, String name) {
    for (ConfigConfiguration config : configs) {
      if (config.getName().equals(name)) {
        return config;
      }
    }
    return null;
  }

  @VisibleForTesting
  boolean validatePipelineConfiguration() {
    boolean preview = true;
    Set<String> stageNames = new HashSet<>();
    boolean shouldBeSource = true;
    for (StageConfiguration stageConf : pipelineConfiguration.getStages()) {
      if (stageNames.contains(stageConf.getInstanceName())) {
        // duplicate stage instance name in the pipeline
        issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0005));
        preview = false;
      }
      StageDefinition stageDef = stageLibrary.getStage(stageConf.getLibrary(), stageConf.getStageName(),
                                                       stageConf.getStageVersion());
      if (stageDef == null) {
        // stage configuration refers to an undefined stage definition
        issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0006,
                                  stageConf.getLibrary(), stageConf.getStageName(), stageConf.getStageVersion()));
        preview = false;
      } else {
        if (shouldBeSource) {
          if (stageDef.getType() != StageType.SOURCE) {
            // first stage must be a Source
            issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0003));
            preview = false;
          }
        } else {
          if (stageDef.getType() == StageType.SOURCE) {
            // no stage other than first stage can be a Source
            issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0004));
            preview = false;
          }
        }
        shouldBeSource = false;
        if (!stageConf.isSystemGenerated() && !TextUtils.isValidName(stageConf.getInstanceName())) {
          // stage instance name has an invalid name (it must match '[0-9A-Za-z_]+')
          issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0016,
                                                 TextUtils.VALID_NAME));
          preview = false;
        }
        for (String lane : stageConf.getInputLanes()) {
          if (!TextUtils.isValidName(lane)) {
            // stage instance input lane has an invalid name (it must match '[0-9A-Za-z_]+')
            issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0017, lane,
                                                   TextUtils.VALID_NAME));
            preview = false;
          }
        }
        for (String lane : stageConf.getOutputLanes()) {
          if (!TextUtils.isValidName(lane)) {
            // stage instance output lane has an invalid name (it must match '[0-9A-Za-z_]+')
            issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0018, lane,
                                                   TextUtils.VALID_NAME));
            preview = false;
          }
        }
        switch (stageDef.getType()) {
          case SOURCE:
            if (!stageConf.getInputLanes().isEmpty()) {
              // source stage cannot have input lanes
              issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0012,
                                                     stageDef.getType(), stageConf.getInputLanes()));
              preview = false;
            }
            if (!stageDef.isVariableOutputStreams()) {
              // source stage must match the output stream defined in StageDef
              if (stageDef.getOutputStreams() != stageConf.getOutputLanes().size()) {
                issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0015,
                                                       stageDef.getOutputStreams(), stageConf.getOutputLanes().size()));
              }
            } else {
              // source stage must have at least one output lane
              issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0032));
            }
            break;
          case PROCESSOR:
            if (stageConf.getInputLanes().isEmpty()) {
              // processor stage must have at least one input lane
              issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0014,
                                                     stageDef.getType()));
              preview = false;
            }
            if (!stageDef.isVariableOutputStreams()) {
              // processor stage must match the output stream defined in StageDef
              if (stageDef.getOutputStreams() != stageConf.getOutputLanes().size()) {
                issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0015,
                                                       stageDef.getOutputStreams(), stageConf.getOutputLanes().size()));
              }
            } else {
              // processor stage must have at least one output lane
              issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0032));
            }
            break;
          case TARGET:
            if (stageConf.getInputLanes().isEmpty()) {
              // target stage must have at least one input lane
              issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0014,
                                                     stageDef.getType()));
              preview = false;
            }
            if (!stageConf.getOutputLanes().isEmpty()) {
              // target stage cannot have output lanes
              issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0013,
                                        stageDef.getType(), stageConf.getOutputLanes()));
              preview = false;
            }
            break;
        }
        for (ConfigDefinition confDef : stageDef.getConfigDefinitions()) {
          if (stageConf.getConfig(confDef.getName()) == null && confDef.isRequired()) {
            // stage configuration does not have a configuration that is required
            issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(), confDef.getName(),
                                                    ValidationError.VALIDATION_0007));
            preview = false;
          }
        }
        for (ConfigConfiguration conf : stageConf.getConfiguration()) {
          ConfigDefinition confDef = stageDef.getConfigDefinition(conf.getName());
          if (confDef == null) {
            // stage configuration defines an invalid configuration
            issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(), conf.getName(),
                                                    ValidationError.VALIDATION_0008));
          } else if (conf.getValue() == null && confDef.isRequired()) {
            // stage configuration has a NULL value for a configuration that requires a value
            String dependsOn = confDef.getDependsOn();
            String[] triggeredBy = confDef.getTriggeredByValues();
            // If the config doesn't depend on anything or the config should be triggered, config is invalid
            if (dependsOn == null || dependsOn.isEmpty() ||
                (Arrays.asList(triggeredBy).contains(String.valueOf(stageConf.getConfig(dependsOn).getValue())))) {
              issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(), confDef.getName(),
                                                      ValidationError.VALIDATION_0007));
              preview = false;
            }
          }
          boolean validateConfig = true;
          if (confDef.getDependsOn() != null &&
              (confDef.getTriggeredByValues() != null && confDef.getTriggeredByValues().length > 0)) {
            String dependsOn = confDef.getDependsOn();
            String[] triggeredBy = confDef.getTriggeredByValues();
            ConfigConfiguration dependsOnConfig = getConfig(stageConf.getConfiguration(), dependsOn);
            if (dependsOnConfig.getValue() != null) {
              validateConfig = false;
              String valueStr = dependsOnConfig.getValue().toString();
              for (String trigger : triggeredBy) {
                validateConfig |= valueStr.equals(trigger);
              }
            }
          }
          if (validateConfig && conf.getValue() != null) {
            switch (confDef.getType()) {
              case BOOLEAN:
                if (!(conf.getValue() instanceof Boolean)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0009,
                                                          confDef.getType()));
                  preview = false;
                }
                break;
              case INTEGER:
                if (!(conf.getValue() instanceof Long || conf.getValue() instanceof Integer)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0009,
                                                          confDef.getType()));
                  preview = false;
                }
                break;
              case STRING:
                if (!(conf.getValue() instanceof String)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0009,
                                                          confDef.getType()));
                  preview = false;
                }
                break;
              case CHARACTER:
                if (!(conf.getValue() instanceof String)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0009,
                                                          confDef.getType()));
                  preview = false;
                } else if (((String)conf.getValue()).length() > 1) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0031,
                                                          conf.getValue()));
                }
                break;
              case MAP:
                if (conf.getValue() instanceof List) {
                  int count = 0;
                  for (Object element : (List) conf.getValue()) {
                    if (element == null) {
                      issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                              confDef.getName(), ValidationError.VALIDATION_0024,
                                                              count));
                      preview = false;
                    } else if (element instanceof Map) {
                      Map map = (Map) element;
                      if (!map.containsKey("key") || !map.containsKey("value")) {
                        issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                                confDef.getName(), ValidationError.VALIDATION_0025,
                                                                count));
                        preview = false;
                      }
                    } else {
                      issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                              confDef.getName(), ValidationError.VALIDATION_0026, count,
                                                              element.getClass().getSimpleName()));
                      preview = false;
                    }
                    count++;
                  }
                } else if (!(conf.getValue() instanceof Map)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0009,
                                                          confDef.getType()));
                  preview = false;
                }
                break;
              case LIST:
                if (!(conf.getValue() instanceof List)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0009,
                                                          confDef.getType()));
                  preview = false;
                }
                break;
              case EL_BOOLEAN:
              case EL_DATE:
              case EL_NUMBER:
                if (!(conf.getValue() instanceof String)) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0029,
                                                          confDef.getType()));
                  preview = false;
                }
                String value = (String) conf.getValue();
                if (!value.startsWith("${") || !value.endsWith("}")) {
                  issues.add(StageIssue.createConfigIssue(stageConf.getInstanceName(), confDef.getGroup(),
                                                          confDef.getName(), ValidationError.VALIDATION_0030, value));
                  preview = false;
                }
                break;
              case EL_STRING:
              case EL_OBJECT:
                break;
              case MODEL:
                preview &= validateModel(stageConf.getInstanceName(), confDef, conf);
                break;
            }
          }
        }
      }
      stageNames.add(stageConf.getInstanceName());
    }
    return preview;
  }

  private boolean validateModel(String instanceName, ConfigDefinition confDef, ConfigConfiguration conf) {
    boolean preview = true;
    switch (confDef.getModel().getModelType()) {
      case VALUE_CHOOSER:
        if(!(conf.getValue() instanceof String || conf.getValue().getClass().isEnum()) ) {
          // stage configuration must be a model
          issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                  ValidationError.VALIDATION_0009, "String"));
          preview = false;
        }
        break;
      case FIELD_SELECTOR_MULTI_VALUED:
        if(!(conf.getValue() instanceof List)) {
          // stage configuration must be a model
          issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                  ValidationError.VALIDATION_0009, "List"));
          preview = false;
        }
        break;
      case FIELD_VALUE_CHOOSER:
        if(!(conf.getValue() instanceof Map)) {
          // stage configuration must be a model
          issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                  ValidationError.VALIDATION_0009, "Map"));
          preview = false;
        }
        break;
      case LANE_PREDICATE_MAPPING:
        if(!(conf.getValue() instanceof List)) {
          // stage configuration must be a model
          issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(), ValidationError.VALIDATION_0009,
                                                  "List<Map>"));
          preview = false;
        } else {
          int count = 0;
          for (Object element : (List) conf.getValue()) {
            if (element instanceof Map) {
              Map map = (Map)element;
              if (!map.containsKey("outputLane")) {
                issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                        ValidationError.VALIDATION_0020, count, "outputLane"));
                preview = false;
              } else {
                if (map.get("outputLane") == null) {
                  issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                          ValidationError.VALIDATION_0021, count, "outputLane"));
                  preview = false;
                } else {
                  if (!(map.get("outputLane") instanceof String)) {
                    issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                            ValidationError.VALIDATION_0022, count, "outputLane"));
                    preview = false;
                  } else if (((String)map.get("outputLane")).isEmpty()) {
                    issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                            ValidationError.VALIDATION_0023, count, "outputLane"));
                    preview = false;
                  }
                }
              }
              if (!map.containsKey("predicate")) {
                issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                        ValidationError.VALIDATION_0020, count, "predicate"));
                preview = false;
              } else {
                if (map.get("predicate") == null) {
                  issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                          ValidationError.VALIDATION_0021, count, "predicate"));
                  preview = false;
                } else {
                  if (!(map.get("predicate") instanceof String)) {
                    issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                            ValidationError.VALIDATION_0022, count, "predicate"));
                    preview = false;
                  } else if (((String)map.get("predicate")).isEmpty()) {
                    issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(),
                                                            ValidationError.VALIDATION_0023, count, "predicate"));
                    preview = false;
                  }
                }
              }
            } else {
              issues.add(StageIssue.createConfigIssue(instanceName, confDef.getGroup(), confDef.getName(), ValidationError.VALIDATION_0019,
                                                      count));
              preview = false;
            }
            count++;
          }
         }
        break;
    }
    return preview;
  }


  @VisibleForTesting
  boolean validatePipelineLanes() {
    boolean preview = true;
    List<StageConfiguration> stagesConf = pipelineConfiguration.getStages();
    for (int i = 0; i < stagesConf.size(); i++) {
      StageConfiguration stageConf = stagesConf.get(i);
      Set<String> openOutputs = new HashSet<>(stageConf.getOutputLanes());
      for (int j = i + 1; j < stagesConf.size(); j++) {
        StageConfiguration downStreamStageConf = stagesConf.get(j);
        Set<String> duplicateOutputs = Sets.intersection(new HashSet<>(stageConf.getOutputLanes()),
                                                         new HashSet<>(downStreamStageConf.getOutputLanes()));
        if (!duplicateOutputs.isEmpty()) {
          // there is more than one stage defining the same output lane
          issues.add(StageIssue.createStageIssue(downStreamStageConf.getInstanceName(),
                                                 ValidationError.VALIDATION_0010,
                                                 duplicateOutputs, stageConf.getInstanceName()));
          preview = false;
        }

        openOutputs.removeAll(downStreamStageConf.getInputLanes());
      }
      if (!openOutputs.isEmpty()) {
        openLanes.addAll(openOutputs);
        // the stage has open output lanes
        issues.add(StageIssue.createStageIssue(stageConf.getInstanceName(), ValidationError.VALIDATION_0011,
                                               openOutputs));
      }
    }
    return preview;
  }

}
