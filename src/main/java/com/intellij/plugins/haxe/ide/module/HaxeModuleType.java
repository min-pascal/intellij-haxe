/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
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
package com.intellij.plugins.haxe.ide.module;

import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class HaxeModuleType extends ModuleType<ModuleBuilder> {
  private static final String MODULE_TYPE_ID = "HAXE_MODULE";
  private static final String HAXE_MODULE_BUILDER_CLASS = "com.intellij.plugins.haxe.ide.module.HaxeModuleBuilder";

  public HaxeModuleType() {
    super(MODULE_TYPE_ID);
  }

  public static HaxeModuleType getInstance() {
    return (HaxeModuleType)ModuleTypeManager.getInstance().findByID(MODULE_TYPE_ID);
  }

  @Override
  public @NotNull String getName() {
    return HaxeBundle.message("haxe.module.type.name");
  }

  @Override
  public @NotNull String getDescription() {
    return HaxeBundle.message("haxe.module.type.description");
  }


  @Override
  public @NotNull Icon getNodeIcon(boolean isOpened) {
    return icons.HaxeIcons.HAXE_LOGO;
  }

  @Override
  public @NotNull ModuleBuilder createModuleBuilder() {
    try {
      Class<?> clazz = Class.forName(HAXE_MODULE_BUILDER_CLASS);
      return (ModuleBuilder) clazz.getDeclaredConstructor().newInstance();
    } catch (Exception | NoClassDefFoundError e) {
      throw new UnsupportedOperationException("Haxe module builder requires Java support plugin", e);
    }
  }


  public ModuleWizardStep @NotNull [] createWizardSteps(final WizardContext wizardContext,
                                                        final ModuleBuilder moduleBuilder,
                                                        final ModulesProvider modulesProvider) {
    HaxeSdkType type = HaxeSdkType.getInstance();
    type.ensureSdk();

    try {
      Class<?> builderClass = Class.forName(HAXE_MODULE_BUILDER_CLASS);
      if (builderClass.isInstance(moduleBuilder)) {
        return new ModuleWizardStep[]{
          new HaxeSdkWizardStep(moduleBuilder, wizardContext, type)
        };
      }
    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
    }
    return ModuleWizardStep.EMPTY_ARRAY;
  }
}
