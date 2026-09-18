package org.communitypoke.luamap.idea.run;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gutter run marker on `.luamap` files — scaffold that surfaces the standard
 * run-gutter UI; it will offer "Run '<file>' via LuaMap Script" once wired to
 * configuration producers. First leaf of each .luamap file only.
 */
public final class LuaMapRunLineMarkerContributor extends RunLineMarkerContributor {

    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        if (element.getContainingFile() == null
                || !element.getContainingFile().getName().endsWith(".luamap")) {
            return null;
        }
        if (element.getPrevSibling() != null) {
            return null; // first leaf only
        }
        return new Info(AllIcons.RunConfigurations.TestState.Run,
                e -> "Run via LuaMap Script (LuaBridge)",
                new com.intellij.openapi.actionSystem.AnAction[0]);
    }
}
