package org.communitypoke.luamap.idea.run;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

public final class LuaMapRunConfigurationOptions extends RunConfigurationOptions {

    private final StoredProperty<String> scriptName =
            string("").provideDelegate(this, "scriptName");
    private final StoredProperty<Integer> bridgePort =
            property(25575).provideDelegate(this, "bridgePort");
    private final StoredProperty<String> bridgeHost =
            string("127.0.0.1").provideDelegate(this, "bridgeHost");

    public String getScriptName() {
        return scriptName.getValue(this);
    }

    public void setScriptName(String v) {
        scriptName.setValue(this, v);
    }

    public int getBridgePort() {
        return bridgePort.getValue(this);
    }

    public void setBridgePort(int v) {
        bridgePort.setValue(this, v);
    }

    public String getBridgeHost() {
        return bridgeHost.getValue(this);
    }

    public void setBridgeHost(String v) {
        bridgeHost.setValue(this, v);
    }
}
