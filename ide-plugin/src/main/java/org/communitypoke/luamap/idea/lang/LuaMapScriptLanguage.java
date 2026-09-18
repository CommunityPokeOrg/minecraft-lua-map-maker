package org.communitypoke.luamap.idea.lang;

import com.intellij.lang.Language;

/** Language marker for {@code .luamap} map scripts (Lua 5.2 + luamap APIs). */
public final class LuaMapScriptLanguage extends Language {
    public static final LuaMapScriptLanguage INSTANCE = new LuaMapScriptLanguage();

    private LuaMapScriptLanguage() {
        super("LuaMapScript");
    }
}
