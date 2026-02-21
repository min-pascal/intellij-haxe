package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class HLRunConfigurationType implements ConfigurationType {

  private final ConfigurationFactory configurationFactory;

  public HLRunConfigurationType() {
    configurationFactory = new HLConfigurationFactory(this);
  }

  @Override
  public String getDisplayName() {
    return "HashLink Debug";
  }

  @Override
  public String getConfigurationTypeDescription() {
    return "HashLink Debug";
  }

  @Override
  public Icon getIcon() {
    return icons.HaxeIcons.HAXE_LOGO;
  }

  @NotNull
  @Override
  @NonNls
  public String getId() {
    return "HLDebugRunConfiguration";
  }

  @Override
  public ConfigurationFactory[] getConfigurationFactories() {
    return new ConfigurationFactory[]{configurationFactory};
  }

  public static class HLConfigurationFactory extends ConfigurationFactory {

    public HLConfigurationFactory(ConfigurationType type) {
      super(type);
    }

    @NotNull
    @Override
    public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
      return new HLRunConfiguration("HashLink Debug", project, this);
    }

    @NotNull
    @Override
    @NonNls
    public String getId() {
      return "HashLink Debug";
    }
  }
}
