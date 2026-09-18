package org.communitypoke.luamap.idea.lang;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** File type for {@code .luamap} scripts. */
public final class LuaMapScriptFileType extends LanguageFileType {
    public static final LuaMapScriptFileType INSTANCE = new LuaMapScriptFileType();

    private LuaMapScriptFileType() {
        super(LuaMapScriptLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "LuaMap Script";
    }

    @Override
    public @NotNull String getDescription() {
        return "Lua Map Maker script (.luamap)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "luamap";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Custom;
    }
}
