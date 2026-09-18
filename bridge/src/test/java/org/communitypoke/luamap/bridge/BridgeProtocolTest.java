package org.communitypoke.luamap.bridge;

import org.communitypoke.luamap.bridge.BridgeProtocol.Request;
import org.communitypoke.luamap.bridge.BridgeProtocol.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeProtocolTest {

    @Test
    void requestRoundTrip() {
        Request r = new Request(7, "eval", "return 1+1", null);
        Request d = BridgeProtocol.decodeRequest(BridgeProtocol.encode(r));
        assertEquals(7, d.id());
        assertEquals("eval", d.op());
        assertEquals("return 1+1", d.code());
        assertNull(d.name());
    }

    @Test
    void requestRoundTripWithName() {
        Request r = new Request(1, "run", null, "parkour");
        Request d = BridgeProtocol.decodeRequest(BridgeProtocol.encode(r));
        assertEquals("run", d.op());
        assertEquals("parkour", d.name());
    }

    @Test
    void requestSpecialCharactersEscape() {
        Request r = new Request(1, "eval", "chat(\"a\\nb\" .. 'q')", null);
        assertEquals(r.code(), BridgeProtocol.decodeRequest(BridgeProtocol.encode(r)).code());
    }

    @Test
    void decodeRejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class,
                () -> BridgeProtocol.decodeRequest("{not json"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeProtocol.decodeRequest("[]"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeProtocol.decodeRequest("{\"id\":1}"));   // no op
        assertThrows(IllegalArgumentException.class,
                () -> BridgeProtocol.decodeRequest("{\"v\":2,\"op\":\"eval\"}"));
    }

    @Test
    void responseRoundTrip() {
        Response ok = Response.ok(3, "segments=30", "[parkour] done");
        Response d = BridgeProtocol.decodeResponse(BridgeProtocol.encode(ok));
        assertTrue(d.ok());
        assertEquals("segments=30", d.result());
        assertEquals("[parkour] done", d.output());
        assertNull(d.error());

        Response err = Response.error(4, "script blew up");
        Response de = BridgeProtocol.decodeResponse(BridgeProtocol.encode(err));
        assertFalse(de.ok());
        assertEquals("script blew up", de.error());
        assertNull(de.result());
    }
}
