package tw.com.aidenmade.rangestreaming.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class PdfProxyController {

    private static final String PDF_SERVER_BASE = "http://localhost:8081/files/";

    @GetMapping("/api/pdf/{filename}")
    public void proxyPdf(
            @PathVariable String filename,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletResponse response) throws IOException {

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        URL url = new URL(PDF_SERVER_BASE + encoded);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);

        if (range != null) {
            conn.setRequestProperty("Range", range);
        }

        int status = conn.getResponseCode();
        response.setStatus(status);
        response.setContentType("application/pdf");
        response.setHeader("Accept-Ranges", "bytes");

        String contentRange = conn.getHeaderField("Content-Range");
        if (contentRange != null) {
            response.setHeader("Content-Range", contentRange);
        }

        String contentLength = conn.getHeaderField("Content-Length");
        if (contentLength != null) {
            response.setHeader("Content-Length", contentLength);
        }

        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
             OutputStream out = response.getOutputStream()) {
            if (in != null) {
                in.transferTo(out);
            }
        } finally {
            conn.disconnect();
        }
    }

    @GetMapping("/api/files")
    public List<Map<String, Object>> listFiles() {
        File dir = new File("file-storage");
        if (!dir.exists() || !dir.isDirectory()) {
            return List.of();
        }
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".pdf"));
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName))
                .map(f -> Map.<String, Object>of("name", f.getName(), "size", f.length()))
                .collect(Collectors.toList());
    }
}
