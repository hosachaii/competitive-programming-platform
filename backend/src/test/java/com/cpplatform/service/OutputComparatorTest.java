package com.cpplatform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputComparatorTest {

    @Test
    void ignoresTrailingNewline() {
        assertTrue(OutputComparator.matches("42\n", "42"));
        assertTrue(OutputComparator.matches("42", "42\n"));
        assertTrue(OutputComparator.matches("42\n\n\n", "42"));
    }

    @Test
    void ignoresTrailingSpacesAndTabsOnEachLine() {
        assertTrue(OutputComparator.matches("hello   \nworld\t\n", "hello\nworld"));
    }

    @Test
    void ignoresLeadingAndTrailingBlankWhitespace() {
        assertTrue(OutputComparator.matches("   \n42\n   ", "42"));
    }

    @Test
    void treatsCrlfAndCrSameAsLf() {
        assertTrue(OutputComparator.matches("a\r\nb\r\nc", "a\nb\nc"));
        assertTrue(OutputComparator.matches("a\rb\rc", "a\nb\nc"));
    }

    @Test
    void preservesWhitespaceWithinALine() {
        // the single space between tokens is significant
        assertFalse(OutputComparator.matches("1 2 3", "123"));
        assertTrue(OutputComparator.matches("1 2 3", "1 2 3"));
    }

    @Test
    void detectsGenuineDifferences() {
        assertFalse(OutputComparator.matches("42", "43"));
        assertFalse(OutputComparator.matches("a\nb", "a\nc"));
    }

    @Test
    void handlesNullAsEmpty() {
        assertTrue(OutputComparator.matches(null, ""));
        assertTrue(OutputComparator.matches(null, "\n"));
        assertFalse(OutputComparator.matches(null, "x"));
    }
}
