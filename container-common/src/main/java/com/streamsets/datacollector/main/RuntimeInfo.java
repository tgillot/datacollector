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
package com.streamsets.datacollector.main;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.streamsets.datacollector.http.WebServerTask;
import com.streamsets.datacollector.util.AuthzRole;
import com.streamsets.datacollector.util.Configuration;
import com.streamsets.lib.security.http.RemoteSSOService;
import com.streamsets.pipeline.api.impl.Utils;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public abstract class RuntimeInfo {
  private static final Logger LOG = LoggerFactory.getLogger(RuntimeInfo.class);

  public static final String SPLITTER = "|";
  public static final String CONFIG_DIR = ".conf.dir";
  public static final String DATA_DIR = ".data.dir";
  public static final String LOG_DIR = ".log.dir";
  public static final String RESOURCES_DIR = ".resources.dir";
  public static final String LIBEXEC_DIR = ".libexec.dir";
  public static final String STATIC_WEB_DIR = ".static-web.dir";
  public static final String TRANSIENT_ENVIRONMENT_SUFFIX = ".transient-env";
  public static final String UNDEF = "UNDEF";
  public static final String CALLBACK_URL = "/public-rest/v1/cluster/callbackWithResponse";
  public static final String SCH_CONF_OVERRIDE = "control-hub-pushed.properties";

  public static final String SDC_PRODUCT = "sdc";

  public static final String SECURITY_PREFIX = "java.security.";
  public static final String PIPELINE_ACCESS_CONTROL_ENABLED = "pipeline.access.control.enabled";
  public static final boolean PIPELINE_ACCESS_CONTROL_ENABLED_DEFAULT = false;
  public static final String DPM_COMPONENT_TYPE_CONFIG = "dpm.componentType";
  public static final String DC_COMPONENT_TYPE = "dc";

  public static final String EMBEDDED_FLAG = "EMBEDDED";

  private boolean DPMEnabled;
  private boolean aclEnabled;
  private boolean remoteSsoDisabled;
  private String deploymentId;
  private String componentType = DC_COMPONENT_TYPE;

  private final static String USER_ROLE = "user";

  public static final String LOG4J_CONFIGURATION_URL_ATTR = "log4j.configuration.url";
  public static final String LOG4J_PROPERTIES = "-log4j.properties";

  private static final String STREAMSETS_LIBRARIES_EXTRA_DIR_SYS_PROP = "STREAMSETS_LIBRARIES_EXTRA_DIR";

  protected static final String BASE_HTTP_URL_ATTR = "%s.base.http.url";

  private final MetricRegistry metrics;
  private final List<? extends ClassLoader> stageLibraryClassLoaders;
  private String httpUrl;
  private String originalHttpUrl;
  private String appAuthToken;
  private final Map<String, Object> attributes;
  private ShutdownHandler shutdownRunnable;
  private final Map<String, String> authenticationTokens;
  protected final String productName;
  protected final String propertyPrefix;
  private final UUID randomUUID;
  private SSLContext sslContext;
  private boolean remoteRegistrationSuccessful;

  public RuntimeInfo(
      String productName,
      String propertyPrefix,
      MetricRegistry metrics,
      List<? extends ClassLoader> stageLibraryClassLoaders
  ) {
    this.metrics = metrics;
    if(stageLibraryClassLoaders != null) {
      this.stageLibraryClassLoaders = ImmutableList.copyOf(stageLibraryClassLoaders);
    } else {
      this.stageLibraryClassLoaders = null;
    }
    this.productName = productName;
    this.propertyPrefix = propertyPrefix;
    httpUrl = UNDEF;
    this.attributes = new ConcurrentHashMap<>();
    authenticationTokens = new HashMap<>();
    reloadAuthenticationToken();
    randomUUID = UUID.randomUUID();
  }

  protected UUID getRandomUUID() {
    return randomUUID;
  }

  public abstract void init();

  public abstract String getId();

  public abstract String getMasterSDCId();

  public abstract String getRuntimeDir();

  public abstract boolean isClusterSlave();

  public MetricRegistry getMetrics() {
    return metrics;
  }

  public void setBaseHttpUrl(String url) {
    this.httpUrl = url;
  }

  public String getBaseHttpUrl() {
    return StringUtils.stripEnd(httpUrl, "/");
  }

  public void setOriginalHttpUrl(String url) {
    this.originalHttpUrl = url;
  }

  public String getOriginalHttpUrl() {
    return StringUtils.stripEnd(originalHttpUrl, "/");
  }

  public String getStaticWebDir() {
    return System.getProperty(propertyPrefix + STATIC_WEB_DIR, getRuntimeDir() + "/" + propertyPrefix + "-static-web");
  }

  public String getConfigDir() {
    return System.getProperty(propertyPrefix + CONFIG_DIR, getRuntimeDir() + "/etc");
  }

  public String getLogDir() {
    return System.getProperty(propertyPrefix + LOG_DIR, getRuntimeDir() + "/log");
  }

  public String getLog4jPropertiesFileName() {
    return propertyPrefix + LOG4J_PROPERTIES;
  }

  public String getDataDir() {
    return System.getProperty(propertyPrefix + DATA_DIR, getRuntimeDir() + "/var");
  }

  public String getLibexecDir() {
    return System.getProperty(propertyPrefix + LIBEXEC_DIR, getRuntimeDir() + "/libexec");
  }

  public String getResourcesDir() {
    return System.getProperty(propertyPrefix + RESOURCES_DIR, getRuntimeDir() + "/resources");
  }

  public String getLibsExtraDir() {
    return System.getProperty(STREAMSETS_LIBRARIES_EXTRA_DIR_SYS_PROP, null);
  }

  public boolean hasAttribute(String key) {
    Utils.checkNotNull(key, "key");
    return attributes.containsKey(key);
  }

  public <T> void setAttribute(String key, T value) {
    Utils.checkNotNull(key, "key");
    attributes.put(key, value);
  }

  public void removeAttribute(String key) {
    Utils.checkNotNull(key, "key");
    attributes.remove(key);
  }

  @SuppressWarnings("unchecked")
  public <T> T getAttribute(String key) {
    Utils.checkNotNull(key, "key");
    return (T) attributes.get(key);
  }

  public List<? extends ClassLoader> getStageLibraryClassLoaders() {
    return stageLibraryClassLoaders;
  }

  public void log(Logger log) {
    log.info("Runtime info:");
    log.info("  Java version  : {}", System.getProperty("java.runtime.version"));
    log.info("  SDC ID        : {}", getId());
    log.info("  Runtime dir   : {}", getRuntimeDir());
    log.info("  Config dir    : {}", getConfigDir());
    log.info("  Data dir      : {}", getDataDir());
    log.info("  Log dir       : {}", getLogDir());
    log.info("  Extra Libs dir: {}", getLibsExtraDir());
  }

  public void setShutdownHandler(ShutdownHandler runnable) {
    shutdownRunnable = runnable;
  }

  public void shutdown(int status) {
    if (shutdownRunnable != null) {
      shutdownRunnable.setExistStatus(status);
      shutdownRunnable.run();
    }
  }

  public Map<String, String> getAuthenticationTokens() {
    return authenticationTokens;
  }

  public boolean isValidAuthenticationToken(String authToken) {
    String [] authTokens = authToken.split(",");
    for(String token: authTokens) {
      String [] strArr = token.split("\\" + SPLITTER);
      if(strArr.length > 1) {
        String role = strArr[1];
        String tokenCache = authenticationTokens.get(role);
        if(!token.equals(tokenCache)) {
          return false;
        }
      } else {
        return  false;
      }
    }
    return true;
  }

  public String [] getRolesFromAuthenticationToken(String authToken) {
    List<String> roles = new ArrayList<>();
    roles.add(USER_ROLE);

    String [] authTokens = authToken.split(",");
    for(String token: authTokens) {
      String [] strArr = token.split("\\" + SPLITTER);
      if(strArr.length > 1) {
        roles.add(strArr[1]);
      }
    }

    return roles.toArray(new String[roles.size()]);
  }

  public void reloadAuthenticationToken() {
    for(String role: AuthzRole.ALL_ROLES) {
      authenticationTokens.put(role, UUID.randomUUID().toString() + SPLITTER + role);
    }
  }

  public String getClusterCallbackURL() {
    return getBaseHttpUrl() + CALLBACK_URL;
  }

  public void setRemoteRegistrationStatus(boolean remoteRegistrationSuccessful) {
    this.remoteRegistrationSuccessful = remoteRegistrationSuccessful;
  }

  public boolean isRemoteRegistrationSuccessful() {
    return this.remoteRegistrationSuccessful;
  }

  public String getDeploymentId() {
    return deploymentId;
  }

  public void setDeploymentId(String deploymentId) {
    this.deploymentId = deploymentId;
  }

  public void setSSLContext(SSLContext sslContext) {
    this.sslContext = sslContext;
  }

  public SSLContext getSSLContext() {
    return sslContext;
  }

  void setAppAuthToken(String appAuthToken) {
    this.appAuthToken = appAuthToken;
  }

  public String getAppAuthToken() {
    return appAuthToken;
  }

  public void setDPMEnabled(boolean DPMEnabled) {
    this.DPMEnabled = DPMEnabled;
  }

  public boolean isDPMEnabled() {
    return DPMEnabled;
  }

  public void setComponentType(String componentType) {
    this.componentType = componentType;
  }

  public String getComponentType() {
    return componentType;
  }

  public boolean isAclEnabled() {
    return aclEnabled;
  }

  public void setAclEnabled(boolean aclEnabled) {
    this.aclEnabled = aclEnabled;
  }

  public boolean isRemoteSsoDisabled() {
    return remoteSsoDisabled;
  }

  public void setRemoteSsoDisabled(boolean remoteSsoDisabled) {
    this.remoteSsoDisabled = remoteSsoDisabled;
  }

  public boolean isTransientEnv() {
    return Boolean.getBoolean(productName + ".transient-env");
  }

  public String getProductName() {
    return productName;
  }

  public String getPropertyPrefix() {
    return propertyPrefix;
  }

  public String getBaseHttpUrlAttr() {
    return getBaseHttpUrlAttr(getProductName());
  }

  public static String getBaseHttpUrlAttr(String productName) {
    return String.format(BASE_HTTP_URL_ATTR, productName);
  }

  public File getPropertiesFile() {
    return new File(getConfigDir(), getProductName() + ".properties");
  }

  public static void loadOrReloadConfigs(RuntimeInfo runtimeInfo, Configuration conf) {
    // Load main SDC configuration as specified by the SDC admin
    //TODO: incorporate product name into properties file location when available
    File configFile = runtimeInfo.getPropertiesFile();
    if (configFile.exists()) {
      try(FileReader reader = new FileReader(configFile)) {
        conf.load(reader);
        runtimeInfo.setBaseHttpUrl(conf.get(runtimeInfo.getBaseHttpUrlAttr(), runtimeInfo.getBaseHttpUrl()));
        String appAuthToken = conf.get(RemoteSSOService.SECURITY_SERVICE_APP_AUTH_TOKEN_CONFIG, "").trim();
        runtimeInfo.setAppAuthToken(appAuthToken);
        boolean isDPMEnabled = conf.get(RemoteSSOService.DPM_ENABLED, RemoteSSOService.DPM_ENABLED_DEFAULT);
        runtimeInfo.setDPMEnabled(isDPMEnabled);
        String componentType = conf.get(DPM_COMPONENT_TYPE_CONFIG, DC_COMPONENT_TYPE).trim();
        runtimeInfo.setComponentType(componentType);
        boolean skipSsoService = conf.get(
            RemoteSSOService.SECURITY_SERVICE_REMOTE_SSO_DISABLED_CONFIG,
            RemoteSSOService.SECURITY_SERVICE_REMOTE_SSO_DISABLED_DEFAULT
        );
        runtimeInfo.setRemoteSsoDisabled(skipSsoService);
        String deploymentId = conf.get(RemoteSSOService.DPM_DEPLOYMENT_ID, null);
        runtimeInfo.setDeploymentId(deploymentId);
        boolean aclEnabled = conf.get(PIPELINE_ACCESS_CONTROL_ENABLED, PIPELINE_ACCESS_CONTROL_ENABLED_DEFAULT);
        String auth = conf.get(WebServerTask.AUTHENTICATION_KEY, WebServerTask.AUTHENTICATION_DEFAULT);
        if (aclEnabled && (!"none".equals(auth) || isDPMEnabled)) {
          runtimeInfo.setAclEnabled(true);
        } else {
          runtimeInfo.setAclEnabled(false);
        }
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    } else {
      LOG.error("Error did not find {}.properties at expected location: {}", runtimeInfo.productName, configFile);
    }

    // Load separate configuration that was pushed down by control hub
    configFile = new File(runtimeInfo.getDataDir(), SCH_CONF_OVERRIDE);
    if(configFile.exists()) {
      try (FileReader reader = new FileReader(configFile)) {
        conf.load(reader);
      } catch (IOException ex) {
        LOG.error("Error did not find {} at expected location: {}", SCH_CONF_OVERRIDE, configFile);
      }
    }

    // Transfer all security properties to the JVM configuration
    for(Map.Entry<String, String> entry : conf.getSubSetConfiguration(SECURITY_PREFIX).getValues().entrySet()) {
      java.security.Security.setProperty(
          entry.getKey().substring(SECURITY_PREFIX.length()),
          entry.getValue()
      );
    }
  }

  /**
   * Store configuration from control hub in persistent manner inside data directory. This configuration will be
   * loaded on data collector start and will override any configuration from sdc.properties.
   *
   * This method call is able to remove existing properties if the value is "null". Please note that the removal will
   * only happen from the 'override' file. This method does not have the capability to remove configuration directly
   * from sdc.properties.
   *
   * @param runtimeInfo RuntimeInfo instance
   * @param newConfigs New set of config properties
   * @throws IOException
   */
  public static void storeControlHubConfigs(
      RuntimeInfo runtimeInfo,
      Map<String, String> newConfigs
  ) throws IOException {
    File configFile = new File(runtimeInfo.getDataDir(), SCH_CONF_OVERRIDE);
    Properties properties = new Properties();

    // Load existing properties from disk if they exists
    if(configFile.exists()) {
      try (FileReader reader = new FileReader(configFile)) {
        properties.load(reader);
      }
    }

    // Propagate updated configuration
    for(Map.Entry<String, String> entry : newConfigs.entrySet()) {
      if(entry.getValue() == null) {
        properties.remove(entry.getKey());
      } else {
        properties.setProperty(entry.getKey(), entry.getValue());
      }
    }

    // Store the new updated configuration back to disk
    try(FileWriter writer = new FileWriter(configFile)) {
      properties.store(writer, null);
    }
  }

  /**
   * Checks whether this RuntimeInfo is in EMBEDDED mode
   * @return true if the {@link #EMBEDDED_FLAG} is present and set to true
   */
  public boolean isEmbedded() {
    return hasAttribute(EMBEDDED_FLAG) && this.<Boolean>getAttribute(EMBEDDED_FLAG);
  }

}
