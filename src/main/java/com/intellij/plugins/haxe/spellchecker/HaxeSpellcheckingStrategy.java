package com.intellij.plugins.haxe.spellchecker;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.lang.psi.HaxeStringLiteralExpression;
import com.intellij.psi.*;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import org.jetbrains.annotations.NotNull;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.DOUBLE_QUOTE;


public class HaxeSpellcheckingStrategy extends SpellcheckingStrategy implements DumbAware {
    private volatile Tokenizer<?> namedElementTokenizer;
    private final HaxeStringLiteralTokenizer stringLiteralTokenizer = new HaxeStringLiteralTokenizer();

    private Tokenizer<?> getNamedElementTokenizer() {
        if (namedElementTokenizer == null) {
            try {
                Class<?> clazz = Class.forName("com.intellij.spellchecker.NamedElementTokenizer");
                namedElementTokenizer = (Tokenizer<?>) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception | NoClassDefFoundError e) {
                namedElementTokenizer = EMPTY_TOKENIZER;
            }
        }
        return namedElementTokenizer;
    }

    @Override
    public @NotNull Tokenizer getTokenizer(PsiElement element) {

        if (element instanceof HaxeMethod haxeMethod && haxeMethod.isConstructor()) return EMPTY_TOKENIZER;

        if (element instanceof HaxeStringLiteralExpression literalExpression) {
            return useTextLevelSpellchecking() ? EMPTY_TOKENIZER : stringLiteralTokenizer;
        }

        if (element instanceof PsiNamedElement) {
            return getNamedElementTokenizer();
        }
        if (shouldIgnore(element)) {
            return EMPTY_TOKENIZER;
        }

        return super.getTokenizer(element);
    }

    private boolean shouldIgnore(PsiElement element) {
        // ignoring other types of comments if grazie spellchecking is enabled
        //  comments are handled by grazie through `HaxeGrazieTextExtractor`
        return element instanceof PsiComment comment && useTextLevelSpellchecking();
    }


    @Override
    public boolean useTextLevelSpellchecking() {
        return Registry.is("spellchecker.grazie.enabled", false);
    }
}
