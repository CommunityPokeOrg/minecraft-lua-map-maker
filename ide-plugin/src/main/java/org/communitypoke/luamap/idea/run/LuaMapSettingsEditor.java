package org.communitypoke.luamap.idea.run;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/** Simple form: script name + bridge port. */
public final class LuaMapSettingsEditor extends SettingsEditor<LuaMapRunConfiguration> {

    private final JPanel panel;
    private final JBTextField script = new JBTextField("parkour");
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(25575, 1, 65535, 1));

    public LuaMapSettingsEditor() {
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Script name:", script)
                .addLabeledComponent("LuaBridge port:", port)
                .getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull LuaMapRunConfiguration cfg) {
        script.setText(cfg.getScriptName());
        port.setValue(cfg.getBridgePort());
    }

    @Override
    protected void applyEditorTo(@NotNull LuaMapRunConfiguration cfg) {
        cfg.setScriptName(script.getText());
        cfg.setBridgePort((Integer) port.getValue());
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}
