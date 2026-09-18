package org.communitypoke.luamap.idea.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.icons.AllIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** "LuaMap Script" run configuration — sends a script to LuaBridge. */
public final class LuaMapRunConfigurationType implements ConfigurationType {

    @Override
    public @NotNull String getDisplayName() {
        return "LuaMap Script";
    }

    @Override
    public String getConfigurationTypeDescription() {
        return "Run a Lua map script on a running game via LuaBridge";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.RunConfigurations.Application;
    }

    @Override
    public @NotNull String getId() {
        return "LUAMAP_RUN";
    }

    @Override
    public ConfigurationFactory[] getConfigurationFactories() {
        return new ConfigurationFactory[]{new LuaMapConfigurationFactory(this)};
    }
}
