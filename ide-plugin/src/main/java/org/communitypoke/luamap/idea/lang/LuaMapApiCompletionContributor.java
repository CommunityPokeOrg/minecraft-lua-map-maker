package org.communitypoke.luamap.idea.lang;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Offers the full LuaMap API surface in .luamap/.lua files. */
public final class LuaMapApiCompletionContributor extends CompletionContributor {

    public LuaMapApiCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement(),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(@NotNull CompletionParameters params,
                                                  @NotNull ProcessingContext ctx,
                                                  @NotNull CompletionResultSet rs) {
                        String file = params.getOriginalFile().getName();
                        if (!(file.endsWith(".luamap") || file.endsWith(".lua"))) {
                            return;
                        }
                        for (Map.Entry<String, java.util.List<String>> e
                                : LuaMapApi.MEMBERS.entrySet()) {
                            for (String member : e.getValue()) {
                                rs.addElement(LookupElementBuilder
                                        .create(e.getKey() + "." + member)
                                        .withTailText(LuaMapApi.lookupTail(e.getKey(), member), true)
                                        .withTypeText("luamap api"));
                            }
                        }
                        for (String g : LuaMapApi.GLOBALS) {
                            rs.addElement(LookupElementBuilder.create(g)
                                    .withTailText("(\"…\")", true)
                                    .withTypeText("luamap api"));
                        }
                    }
                });
    }
}
