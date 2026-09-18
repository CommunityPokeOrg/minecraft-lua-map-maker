package org.communitypoke.luamap.idea.run;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.process.NopProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.runners.ExecutionEnvironment;
import org.communitypoke.luamap.idea.bridge.LuaBridgeClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Executes a {@link LuaMapRunConfiguration}: connects to LuaBridge and sends a
 * {@code run} (or {@code eval} when the config names a file that doesn't
 * exist on the server) op, streaming the response into a console.
 *
 * <p>Current state: single-shot run + output. A true debugger session
 * (break/step) is a protocol + mod feature tracked in docs/luabridge.md.
 */
public final class LuaMapRunProfileState implements RunProfileState {

    private final ExecutionEnvironment env;
    private final LuaMapRunConfiguration cfg;

    LuaMapRunProfileState(ExecutionEnvironment env, LuaMapRunConfiguration cfg) {
        this.env = env;
        this.cfg = cfg;
    }

    @Override
    public @Nullable ExecutionResult execute(Executor executor,
                                             @NotNull ProgramRunner<?> runner) {
        BridgeProcess handler = new BridgeProcess();
        handler.startNotify();
        handler.start();
        ExecutionConsole console = new ConsoleViewImpl(env.getProject(), true);
        return new DefaultExecutionResult(console, handler);
    }

    private final class BridgeProcess extends NopProcessHandler {
        void start() {
            new Thread(() -> {
                try {
                    notifyTextAvailable("[luamap] connecting to 127.0.0.1:"
                            + cfg.getBridgePort() + " ...\n", ProcessOutputTypes.STDOUT);
                    try (LuaBridgeClient c = new LuaBridgeClient(
                            cfg.getOptions().getBridgeHost(), cfg.getBridgePort())) {
                        LuaBridgeClient.Reply r = c.run(cfg.getScriptName());
                        if (r.output() != null) {
                            notifyTextAvailable(r.output() + "\n", ProcessOutputTypes.STDOUT);
                        }
                        if (r.ok()) {
                            notifyTextAvailable("[luamap] ok"
                                    + (r.result() != null ? ": " + r.result() : "")
                                    + "\n", ProcessOutputTypes.STDOUT);
                        } else {
                            notifyTextAvailable("[luamap] error: " + r.error()
                                    + "\n", ProcessOutputTypes.STDERR);
                        }
                    }
                } catch (IOException e) {
                    notifyTextAvailable("[luamap] bridge unreachable: " + e.getMessage()
                            + "\n(start the game with --bridgePort "
                            + cfg.getBridgePort() + ")\n", ProcessOutputTypes.STDERR);
                } finally {
                    notifyProcessTerminated(0);
                }
            }, "luamap-bridge-call").start();
        }
    }
}
