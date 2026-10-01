package com.mikumc.motd.ping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 协议段展开的格式与边界测试。 */
class PingRegistryTest {

    @Test
    void expandsSingleValue() {
        assertArrayEquals(new int[]{761}, PingRegistry.expandRange("761", LoggerFactory.getLogger("test")));
    }

    @Test
    void expandsInclusiveRange() {
        assertArrayEquals(new int[]{757, 758, 759}, PingRegistry.expandRange("757-759", LoggerFactory.getLogger("test")));
    }

    @Test
    void trimsWhitespaceAroundBounds() {
        assertArrayEquals(new int[]{5, 6}, PingRegistry.expandRange(" 5 - 6 ", LoggerFactory.getLogger("test")));
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> PingRegistry.expandRange("abc", LoggerFactory.getLogger("test")));
    }

    @Test
    void rejectsReversedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> PingRegistry.expandRange("800-700", LoggerFactory.getLogger("test")));
    }

    @Test
    void truncatesOversizedRange() {
        int[] expanded = PingRegistry.expandRange("0-999999", LoggerFactory.getLogger("test"));
        assertEquals(4096, expanded.length);
        assertEquals(0, expanded[0]);
        assertEquals(4095, expanded[expanded.length - 1]);
    }
}
