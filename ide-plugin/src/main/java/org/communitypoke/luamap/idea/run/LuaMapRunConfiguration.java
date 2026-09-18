package org.communitypoke.luamap.idea.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Editable run configuration: bridge host/port + script name to run. */
public final class LuaMapRunConfiguration extends RunConfigurationBase<LuaMapRunConfigurationOptions> {

    protected LuaMapRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override
    protected @NotNull Class<? extends LuaMapRunConfigurationOptions> getOptionsClass() {
        return LuaMapRunConfigurationOptions.class;
    }

    @Override
    protected @NotNull LuaMapRunConfigurationOptions getOptions() {
        return (LuaMapRunConfigurationOptions) super.getOptions();
    }

    public String getScriptName() {
        return getOptions().getScriptName();
    }

    public void setScriptName(String name) {
        getOptions().setScriptName(name);
    }

    public int getBridgePort() {
        return getOptions().getBridgePort();
    }

    public void setBridgePort(int port) {
        getOptions().setBridgePort(port);
    }

    @Override
    public @NotNull SettingsEditor<? extends com.intellij.execution.configurations.RunConfiguration> getConfigurationEditor() {
        return new LuaMapSettingsEditor();
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (getScriptName() == null || getScriptName().isBlank()) {
            throw new RuntimeConfigurationException("Script name is required (e.g. 'parkour' or 'my_map')");
        }
        if (getBridgePort() <= 0 || getBridgePort() > 65535) {
            throw new RuntimeConfigurationException("Bridge port must be 1-65535 (launcher --bridgePort)");
        }
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor,
                                              @NotNull ExecutionEnvironment environment) {
        return new LuaMapRunProfileState(environment, this);
    }
}
