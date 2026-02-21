package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class HLRunConfigurationEditor extends SettingsEditor<HLRunConfiguration> {

  private final Project project;

  private TextFieldWithBrowseButton hlExecutableField;
  private TextFieldWithBrowseButton programPathField;
  private TextFieldWithBrowseButton workingDirectoryField;
  private TextFieldWithBrowseButton adapterPathField;
  private TextFieldWithBrowseButton nodePathField;
  private JTextField debugPortField;
  private JTextField programArgumentsField;
  private DefaultListModel<String> sourcePathsModel;
  private JList<String> sourcePathsList;
  private JPanel rootPanel;

  public HLRunConfigurationEditor(Project project) {
    this.project = project;
  }

  @NotNull
  @Override
  protected JComponent createEditor() {
    rootPanel = new JPanel(new BorderLayout());
    JPanel inner = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(4, 4, 4, 4);
    gbc.fill = GridBagConstraints.HORIZONTAL;
    int row = 0;

    // --- HashLink section ---
    gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;
    inner.add(createSectionLabel("HashLink"), gbc);
    row++;
    gbc.gridwidth = 1;

    // HL executable
    hlExecutableField = new TextFieldWithBrowseButton();
    hlExecutableField.addBrowseFolderListener(
        "Select HashLink Executable", "Path to the HashLink (hl) executable",
        project, FileChooserDescriptorFactory.createSingleFileDescriptor());
    row = addLabeledRow(inner, gbc, row, "HL executable:", hlExecutableField);

    // Program (.hl file)
    programPathField = new TextFieldWithBrowseButton();
    programPathField.addBrowseFolderListener(
        "Select Program File", "Path to the .hl bytecode file",
        project, FileChooserDescriptorFactory.createSingleFileDescriptor("hl"));
    row = addLabeledRow(inner, gbc, row, "Program (.hl file):", programPathField);

    // Working directory
    workingDirectoryField = new TextFieldWithBrowseButton();
    workingDirectoryField.addBrowseFolderListener(
        "Select Working Directory", "Working directory for the debug session",
        project, FileChooserDescriptorFactory.createSingleFolderDescriptor());
    row = addLabeledRow(inner, gbc, row, "Working directory:", workingDirectoryField);

    // Program arguments
    programArgumentsField = new JTextField();
    row = addLabeledRow(inner, gbc, row, "Program arguments:", programArgumentsField);

    // --- Debug Adapter section ---
    gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;
    inner.add(createSectionLabel("Debug Adapter"), gbc);
    row++;
    gbc.gridwidth = 1;

    // Adapter entry script
    adapterPathField = new TextFieldWithBrowseButton();
    adapterPathField.addBrowseFolderListener(
        "Select Adapter Script", "Path to the DAP adapter entry script (.js)",
        project, FileChooserDescriptorFactory.createSingleFileDescriptor("js"));
    row = addLabeledRow(inner, gbc, row, "Adapter entry script:", adapterPathField);

    // Node.js executable
    nodePathField = new TextFieldWithBrowseButton();
    nodePathField.addBrowseFolderListener(
        "Select Node.js Executable", "Path to the Node.js executable",
        project, FileChooserDescriptorFactory.createSingleFileDescriptor());
    row = addLabeledRow(inner, gbc, row, "Node.js executable:", nodePathField);

    // Debug port
    debugPortField = new JTextField(5);
    row = addLabeledRow(inner, gbc, row, "Debug port:", debugPortField);

    // --- Source Paths section ---
    gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;
    inner.add(createSectionLabel("Source Paths"), gbc);
    row++;
    gbc.gridwidth = 1;

    // Source paths list
    sourcePathsModel = new DefaultListModel<>();
    sourcePathsList = new JList<>(sourcePathsModel);
    sourcePathsList.setVisibleRowCount(5);
    JScrollPane scrollPane = new JScrollPane(sourcePathsList);

    JPanel sourcePathsPanel = new JPanel(new BorderLayout(4, 4));
    sourcePathsPanel.add(scrollPane, BorderLayout.CENTER);

    JPanel buttonPanel = new JPanel(new GridLayout(2, 1, 0, 4));
    JButton addButton = new JButton("+");
    addButton.addActionListener(e -> {
      VirtualFile file = FileChooser.chooseFile(
          FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null);
      if (file != null) {
        sourcePathsModel.addElement(FileUtil.toSystemIndependentName(file.getPath()));
      }
    });
    JButton removeButton = new JButton("-");
    removeButton.addActionListener(e -> {
      int index = sourcePathsList.getSelectedIndex();
      if (index >= 0) {
        sourcePathsModel.remove(index);
      }
    });
    buttonPanel.add(addButton);
    buttonPanel.add(removeButton);
    sourcePathsPanel.add(buttonPanel, BorderLayout.EAST);

    gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
    inner.add(new JLabel("Haxe source dirs:"), gbc);
    gbc.gridx = 1; gbc.weightx = 1.0;
    inner.add(sourcePathsPanel, gbc);
    row++;

    // Filler to push everything to the top
    gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weighty = 1.0;
    gbc.fill = GridBagConstraints.BOTH;
    inner.add(new JPanel(), gbc);

    rootPanel.add(inner, BorderLayout.CENTER);
    return rootPanel;
  }

  private int addLabeledRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
    gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
    panel.add(new JLabel(label), gbc);
    gbc.gridx = 1; gbc.weightx = 1.0;
    panel.add(field, gbc);
    return row + 1;
  }

  private JComponent createSectionLabel(String text) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
    return label;
  }

  @Override
  protected void resetEditorFrom(@NotNull HLRunConfiguration config) {
    hlExecutableField.setText(config.getHlExecutablePath());
    programPathField.setText(config.getProgramPath());
    workingDirectoryField.setText(config.getWorkingDirectory());
    adapterPathField.setText(config.getAdapterPath());
    nodePathField.setText(config.getNodePath());
    debugPortField.setText(String.valueOf(config.getDebugPort()));
    programArgumentsField.setText(config.getProgramArguments());

    sourcePathsModel.clear();
    for (String path : config.getSourcePaths()) {
      sourcePathsModel.addElement(path);
    }
  }

  @Override
  protected void applyEditorTo(@NotNull HLRunConfiguration config) {
    config.setHlExecutablePath(hlExecutableField.getText());
    config.setProgramPath(programPathField.getText());
    config.setWorkingDirectory(workingDirectoryField.getText());
    config.setAdapterPath(adapterPathField.getText());
    config.setNodePath(nodePathField.getText());
    config.setProgramArguments(programArgumentsField.getText());

    int port;
    try {
      port = Integer.parseInt(debugPortField.getText());
    } catch (NumberFormatException e) {
      port = 6112;
    }
    config.setDebugPort(port);

    List<String> paths = new ArrayList<>();
    for (int i = 0; i < sourcePathsModel.getSize(); i++) {
      paths.add(sourcePathsModel.getElementAt(i));
    }
    config.setSourcePaths(paths);
  }
}
