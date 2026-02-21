package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.configurations.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class HLRunConfiguration extends RunConfigurationBase<RunProfileState> {

  private String hlExecutablePath = "hl";
  private String programPath = "";
  private String workingDirectory = "";
  private String adapterPath = "";
  private String nodePath = "node";
  @XCollection(style = XCollection.Style.v2)
  private List<String> sourcePaths = new ArrayList<>();
  private int debugPort = 6112;
  private String programArguments = "";

  public HLRunConfiguration(String name, Project project, ConfigurationFactory factory) {
    super(project, factory, name);
  }

  // --- Getters ---

  public String getHlExecutablePath() {
    return hlExecutablePath;
  }

  public String getProgramPath() {
    return programPath;
  }

  public String getWorkingDirectory() {
    return workingDirectory;
  }

  public String getAdapterPath() {
    return adapterPath;
  }

  public String getNodePath() {
    return nodePath;
  }

  public List<String> getSourcePaths() {
    return sourcePaths;
  }

  public int getDebugPort() {
    return debugPort;
  }

  public String getProgramArguments() {
    return programArguments;
  }

  // --- Setters ---

  public void setHlExecutablePath(String hlExecutablePath) {
    this.hlExecutablePath = hlExecutablePath;
  }

  public void setProgramPath(String programPath) {
    this.programPath = programPath;
  }

  public void setWorkingDirectory(String workingDirectory) {
    this.workingDirectory = workingDirectory;
  }

  public void setAdapterPath(String adapterPath) {
    this.adapterPath = adapterPath;
  }

  public void setNodePath(String nodePath) {
    this.nodePath = nodePath;
  }

  public void setSourcePaths(List<String> sourcePaths) {
    this.sourcePaths = sourcePaths;
  }

  public void setDebugPort(int debugPort) {
    this.debugPort = debugPort;
  }

  public void setProgramArguments(String programArguments) {
    this.programArguments = programArguments;
  }

  // --- Persistence ---

  @Override
  public void writeExternal(@NotNull Element element) throws WriteExternalException {
    super.writeExternal(element);
    XmlSerializer.serializeInto(this, element);
  }

  @Override
  public void readExternal(@NotNull Element element) throws InvalidDataException {
    super.readExternal(element);
    XmlSerializer.deserializeInto(this, element);
  }

  // --- Validation ---

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    if (hlExecutablePath == null || hlExecutablePath.isBlank()) {
      throw new RuntimeConfigurationException("HashLink executable path must not be empty");
    }
    if (programPath == null || programPath.isBlank()) {
      throw new RuntimeConfigurationException("Program path must not be empty");
    }
    if (adapterPath == null || adapterPath.isBlank()) {
      throw new RuntimeConfigurationException("Adapter path must not be empty");
    }
    if (nodePath == null || nodePath.isBlank()) {
      throw new RuntimeConfigurationException("Node.js executable path must not be empty");
    }
    if (debugPort < 1 || debugPort > 65535) {
      throw new RuntimeConfigurationException("Debug port must be between 1 and 65535");
    }
  }

  // --- Editor ---

  @NotNull
  @Override
  public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new HLRunConfigurationEditor(getProject());
  }

  // --- State ---

  @Override
  public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment)
      throws ExecutionException {
    return new HLDebuggerState(this, environment);
  }

  @NotNull
  public Collection<Module> getValidModules() {
    return Collections.emptyList();
  }
}
