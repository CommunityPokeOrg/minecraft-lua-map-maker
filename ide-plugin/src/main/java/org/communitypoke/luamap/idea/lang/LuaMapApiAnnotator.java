package org.communitypoke.luamap.idea.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight "syntax" support: highlights LuaMap API words wherever they
 * appear in {@code .luamap} files or plain-text {@code .lua} scripts. A real
 * Lua PSI isn't available without the Lua plugin, so this annotator matches
 * word tokens directly.
 */
public final class LuaMapApiAnnotator implements Annotator {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        // Only leaf elements, and only in files that look like luamap scripts.
        if (element.getChildren().length > 0) {
            return;
        }
        String name = element.getContainingFile().getName();
        if (!(name.endsWith(".luamap") || name.endsWith(".lua"))) {
            return;
        }
        String text = element.getText();
        if (text == null || text.length() < 2) {
            return;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group();
            boolean api = LuaMapApi.ROOTS.contains(w) || LuaMapApi.GLOBALS.contains(w);
            if (api) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(new TextRange(
                                element.getTextRange().getStartOffset() + m.start(),
                                element.getTextRange().getStartOffset() + m.end()))
                        .textAttributes(DefaultLanguageHighlighterColors.GLOBAL_VARIABLE)
                        .create();
            }
        }
    }
}
