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

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Editors provider for HashLink debugging.
 * 
 * This provides the document/PSI support for expression evaluation
 * in watches, conditional breakpoints, and the evaluate dialog.
 */
public class HLDebuggerEditorsProvider extends XDebuggerEditorsProvider {

    @NotNull
    @Override
    public FileType getFileType() {
        return HaxeFileType.INSTANCE;
    }

    @NotNull
    @Override
    public Document createDocument(@NotNull Project project,
                                    @NotNull String text,
                                    @Nullable XSourcePosition sourcePosition,
                                    @NotNull EvaluationMode mode) {
        // Create a Haxe file fragment for expression evaluation
        PsiFile psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("HLDebugExpression.hx", HaxeFileType.INSTANCE, text);
        
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        
        if (document != null) {
            return document;
        }
        
        // Fallback: create an empty document
        return com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(text);
    }
}
