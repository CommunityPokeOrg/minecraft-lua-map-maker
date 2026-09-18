package org.communitypoke.luamap;

import org.communitypoke.luamap.lua.BlockStates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockStatesTest {

    @Test
    void normalizeIdDefaultsNamespace() {
        assertEquals("minecraft:stone", BlockStates.normalizeId("stone"));
        assertEquals("minecraft:chest", BlockStates.normalizeId("chest[facing=north]"));
        assertEquals("mod:thing", BlockStates.normalizeId("mod:thing"));
        assertEquals("mod:thing", BlockStates.normalizeId("mod:thing[a=b]"));
    }
}
