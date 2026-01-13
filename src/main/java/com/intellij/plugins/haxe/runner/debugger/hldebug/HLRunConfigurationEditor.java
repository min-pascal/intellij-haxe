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

import com.intellij.execution.ui.CommonProgramParametersPanel;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Settings editor for HashLink run configuration.
 * 
 * This provides the UI for editing HashLink debug configuration settings
 * in the Run/Debug Configurations dialog.
 */
public class HLRunConfigurationEditor extends SettingsEditor<HLRunConfiguration> {

    private final Project project;
    
    private JPanel mainPanel;
    private TextFieldWithBrowseButton hlExecutableField;
    private TextFieldWithBrowseButton programPathField;
    private TextFieldWithBrowseButton workingDirectoryField;
    private JBTextField programArgumentsField;
    private JBTextField debugPortField;

    public HLRunConfigurationEditor(@NotNull Project project) {
        this.project = project;
    }

    @Override
    protected void resetEditorFrom(@NotNull HLRunConfiguration configuration) {
        hlExecutableField.setText(configuration.getHlExecutablePath());
        programPathField.setText(configuration.getProgramPath());
        workingDirectoryField.setText(configuration.getWorkingDirectory());
        programArgumentsField.setText(configuration.getProgramArguments());
        debugPortField.setText(String.valueOf(configuration.getDebugPort()));
    }

    @Override
    protected void applyEditorTo(@NotNull HLRunConfiguration configuration) {
        configuration.setHlExecutablePath(hlExecutableField.getText().trim());
        configuration.setProgramPath(programPathField.getText().trim());
        configuration.setWorkingDirectory(workingDirectoryField.getText().trim());
        configuration.setProgramArguments(programArgumentsField.getText().trim());
        
        try {
            configuration.setDebugPort(Integer.parseInt(debugPortField.getText().trim()));
        } catch (NumberFormatException e) {
            configuration.setDebugPort(HLDebugRunner.DEFAULT_DEBUG_PORT);
        }
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        createUIComponents();
        return mainPanel;
    }

    private void createUIComponents() {
        // HashLink executable path
        hlExecutableField = new TextFieldWithBrowseButton();
        hlExecutableField.addBrowseFolderListener(
            "Select HashLink Executable",
            "Select the path to the HashLink (hl) executable",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        );
        hlExecutableField.setText("hl"); // Default to PATH

        // Program path (.hl file)
        programPathField = new TextFieldWithBrowseButton();
        FileChooserDescriptor hlFileDescriptor = new FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter(file -> file.getName().endsWith(".hl"))
            .withTitle("Select HashLink Program")
            .withDescription("Select the .hl bytecode file to debug");
        programPathField.addBrowseFolderListener(
            "Select HashLink Program",
            "Select the .hl bytecode file to debug",
            project,
            hlFileDescriptor
        );

        // Working directory
        workingDirectoryField = new TextFieldWithBrowseButton();
        workingDirectoryField.addBrowseFolderListener(
            "Select Working Directory",
            "Select the working directory for the debugged program",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        );
        if (project.getBasePath() != null) {
            workingDirectoryField.setText(project.getBasePath());
        }

        // Program arguments
        programArgumentsField = new JBTextField();

        // Debug port
        debugPortField = new JBTextField();
        debugPortField.setText(String.valueOf(HLDebugRunner.DEFAULT_DEBUG_PORT));

        // Build the form
        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("HashLink executable:"), hlExecutableField)
            .addLabeledComponent(new JBLabel("Program (.hl file):"), programPathField)
            .addLabeledComponent(new JBLabel("Working directory:"), workingDirectoryField)
            .addLabeledComponent(new JBLabel("Program arguments:"), programArgumentsField)
            .addLabeledComponent(new JBLabel("Debug port:"), debugPortField)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }
}
