package org.communitypoke.luamap.bridge;

import org.communitypoke.luamap.bridge.BridgeProtocol.Request;
import org.communitypoke.luamap.bridge.BridgeProtocol.Response;

/**
 * What a LuaBridge server does with a request. Implemented by the mod
 * (dispatching onto the server thread) or by test fakes.
 */
@FunctionalInterface
public interface BridgeExecutor {
    Response handle(Request request);
}
