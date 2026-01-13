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

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.plugins.haxe.HaxeBundle;
import icons.HaxeIcons;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Configuration type for HashLink debugging.
 * 
 * This registers the "HashLink" run configuration type in IntelliJ's
 * Run/Debug Configurations dialog.
 */
public class HLRunConfigurationType implements ConfigurationType {

    public static final String ID = "HLRunConfiguration";

    private final ConfigurationFactory factory;

    public HLRunConfigurationType() {
        factory = new HLConfigurationFactory(this);
    }

    @Override
    public @NotNull @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return HaxeBundle.message("haxe.hl.run.configuration.name", "HashLink");
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Sentence) String getConfigurationTypeDescription() {
        return HaxeBundle.message("haxe.hl.run.configuration.description", 
            "Run and debug HashLink (.hl) applications");
    }

    @Override
    public Icon getIcon() {
        // TODO: Add a HashLink-specific icon
        return HaxeIcons.HAXE_LOGO;
    }

    @Override
    public @NotNull @NonNls String getId() {
        return ID;
    }

    @Override
    public ConfigurationFactory[] getConfigurationFactories() {
        return new ConfigurationFactory[]{factory};
    }

    public static HLRunConfigurationType getInstance() {
        return ConfigurationType.CONFIGURATION_TYPE_EP.findExtensionOrFail(HLRunConfigurationType.class);
    }

    /**
     * Factory for creating HashLink run configurations.
     */
    public static class HLConfigurationFactory extends ConfigurationFactory {

        public HLConfigurationFactory(@NotNull ConfigurationType type) {
            super(type);
        }

        @Override
        public @NotNull @NonNls String getId() {
            return HLRunConfigurationType.ID;
        }

        @Override
        public @NotNull String getName() {
            return "HashLink";
        }

        @Override
        public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new HLRunConfiguration(project, this, "HashLink");
        }
    }
}
