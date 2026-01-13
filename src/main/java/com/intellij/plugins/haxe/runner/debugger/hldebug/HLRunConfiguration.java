/*
 * Copyright 2024 Haxe Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Run configuration for HashLink debugging.
 * 
 * This configuration stores the settings needed to debug a HashLink application,
 * including the path to the HL executable, the .hl program file, and debug options.
 */
public class HLRunConfiguration extends RunConfigurationBase<HLRunConfiguration> {

    private String hlExecutablePath = "hl";
    private String programPath = "";
    private String workingDirectory = "";
    private String programArguments = "";
    private int debugPort = HLDebugRunner.DEFAULT_DEBUG_PORT;
    private Map<String, String> environmentVariables = new HashMap<>();
    private boolean passParentEnv = true;

    public HLRunConfiguration(@NotNull Project project,
                               @NotNull ConfigurationFactory factory,
                               @NotNull String name) {
        super(project, factory, name);
    }

    // ==================== Getters and Setters ====================

    public String getHlExecutablePath() {
        return hlExecutablePath;
    }

    public void setHlExecutablePath(String hlExecutablePath) {
        this.hlExecutablePath = hlExecutablePath;
    }

    public String getProgramPath() {
        return programPath;
    }

    public void setProgramPath(String programPath) {
        this.programPath = programPath;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getProgramArguments() {
        return programArguments;
    }

    public void setProgramArguments(String programArguments) {
        this.programArguments = programArguments;
    }

    public int getDebugPort() {
        return debugPort;
    }

    public void setDebugPort(int debugPort) {
        this.debugPort = debugPort;
    }

    public Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public boolean isPassParentEnv() {
        return passParentEnv;
    }

    public void setPassParentEnv(boolean passParentEnv) {
        this.passParentEnv = passParentEnv;
    }

    // ==================== Configuration ====================

    @NotNull
    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new HLRunConfigurationEditor(getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (hlExecutablePath == null || hlExecutablePath.isEmpty()) {
            throw new RuntimeConfigurationError("HashLink executable path is not specified");
        }
        
        if (programPath == null || programPath.isEmpty()) {
            throw new RuntimeConfigurationError("Program path (.hl file) is not specified");
        }
        
        if (!programPath.endsWith(".hl")) {
            throw new RuntimeConfigurationWarning("Program path should be a .hl file");
        }
    }

    @Nullable
    @Override
    public RunProfileState getState(@NotNull Executor executor,
                                     @NotNull ExecutionEnvironment environment) throws ExecutionException {
        return new HLDebuggerState(environment, this);
    }

    // ==================== Serialization ====================

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        XmlSerializer.deserializeInto(this, element);
    }

    @Override
    public void writeExternal(@NotNull Element element) throws WriteExternalException {
        super.writeExternal(element);
        XmlSerializer.serializeInto(this, element);
    }

    @Override
    public HLRunConfiguration clone() {
        HLRunConfiguration clone = (HLRunConfiguration) super.clone();
        clone.hlExecutablePath = this.hlExecutablePath;
        clone.programPath = this.programPath;
        clone.workingDirectory = this.workingDirectory;
        clone.programArguments = this.programArguments;
        clone.debugPort = this.debugPort;
        clone.environmentVariables = new HashMap<>(this.environmentVariables);
        clone.passParentEnv = this.passParentEnv;
        return clone;
    }
}
