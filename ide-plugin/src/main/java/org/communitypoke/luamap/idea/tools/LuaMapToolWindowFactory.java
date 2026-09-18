package org.communitypoke.luamap.idea.tools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * "LuaMap" tool window — live LuaBridge panel: connection management, NPC
 * inspector, world/block queries, script runner and eval console. The panel
 * is {@link com.intellij.openapi.Disposable Disposable}; registering it with
 * the window closes the session (socket + poll/reconnect threads) when the
 * tool window content or project is disposed.
 */
public final class LuaMapToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        LuaMapToolWindowPanel panel = new LuaMapToolWindowPanel();
        var content = ContentFactory.getInstance()
                .createContent(panel, "LuaBridge", false);
        // Dispose the session with the tool window content.
        Disposer.register(content, panel);
        window.getContentManager().addContent(content);
    }
}
