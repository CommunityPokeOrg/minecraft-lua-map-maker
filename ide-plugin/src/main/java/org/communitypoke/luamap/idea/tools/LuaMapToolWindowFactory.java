package org.communitypoke.luamap.idea.tools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * "LuaMap" tool window — scaffold for a block-preview/inspector panel.
 * Will show region previews fetched via LuaBridge `eval world.getblock(...)`
 * and NPC positions from `npc.list()`.
 */
public final class LuaMapToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        JPanel panel = new JPanel(new BorderLayout());
        JBLabel label = new JBLabel(
                "<html><b>LuaMap</b> — block preview / NPC inspector<br/><br/>"
                        + "Not connected. Start the game with<br/>"
                        + "<code>--bridgePort 25575</code> and a connection panel will appear here.</html>");
        label.setBorder(JBUI.Borders.empty(12));
        panel.add(label, BorderLayout.NORTH);
        window.getContentManager().addContent(
                ContentFactory.getInstance().createContent(panel, "Preview", false));
    }
}
