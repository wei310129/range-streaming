package tw.com.aidenmade.rangestreaming.pdf;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfFileControllerRangeToggleTest {

    @Test
    void shouldReturn206WhenRangeEnabledAndRangeHeaderProvided() throws IOException {
        PdfFileStore store = mock(PdfFileStore.class);
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        when(store.get("demo.pdf")).thenReturn(content);

        PdfFileController controller = new PdfFileController(store, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.serveFile("demo.pdf", "bytes=2-5", response);

        assertEquals(206, response.getStatus());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("bytes 2-5/10", response.getHeader("Content-Range"));
        assertEquals("4", response.getHeader("Content-Length"));
        assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
    }

    @Test
    void shouldReturn200FullContentWhenRangeDisabledEvenIfRangeHeaderProvided() throws IOException {
        PdfFileStore store = mock(PdfFileStore.class);
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        when(store.get("demo.pdf")).thenReturn(content);

        PdfFileController controller = new PdfFileController(store, false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.serveFile("demo.pdf", "bytes=2-5", response);

        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Accept-Ranges"));
        assertNull(response.getHeader("Content-Range"));
        assertEquals("10", response.getHeader("Content-Length"));
        assertArrayEquals(content, response.getContentAsByteArray());
    }
}

