package tw.com.aidenmade.rangestreaming.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PdfProxyControllerRangeParseTest {

    @Test
    void shouldParseOpenEndedRange() {
        assertArrayEquals(new int[]{5, 9}, PdfProxyController.parseRange("bytes=5-", 10));
    }

    @Test
    void shouldParseClosedRange() {
        assertArrayEquals(new int[]{2, 6}, PdfProxyController.parseRange("bytes=2-6", 10));
    }

    @Test
    void shouldParseSuffixRange() {
        assertArrayEquals(new int[]{7, 9}, PdfProxyController.parseRange("bytes=-3", 10));
    }

    @Test
    void shouldRejectInvalidOrUnsupportedRanges() {
        assertNull(PdfProxyController.parseRange("bytes=", 10));
        assertNull(PdfProxyController.parseRange("bytes=9-2", 10));
        assertNull(PdfProxyController.parseRange("bytes=10-", 10));
        assertNull(PdfProxyController.parseRange("bytes=1-2,4-6", 10));
    }
}

