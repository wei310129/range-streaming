package tw.com.aidenmade.rangestreaming.api;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API Server（8080）的代理層。
 *
 * 架構中的角色：
 *   Browser → [API Server(:8080)] → PDF Server(:8081) → file-storage/
 *
 * 為何需要代理，而不讓前端直接呼叫 PDF Server？
 *   - 隔離：前端只知道 API Server，不直接暴露內部的檔案伺服器位址
 *   - 擴展性：未來可在這層加入驗證、限流、日誌等邏輯
 *   - 模擬真實架構：CDN / 反向代理 / BFF 都是類似的概念
 */
@RestController
public class PdfProxyController {

    private static final Logger log = LoggerFactory.getLogger(PdfProxyController.class);
    private static final String PDF_SERVER_BASE = "http://localhost:8081/files/";

    /**
     * Range Request 代理端點。
     *
     * 完整的 HTTP Range Request 流程：
     *
     *  ┌─ 第一次下載（無 Range header）─────────────────────────┐
     *  │  Browser  →  GET /api/pdf/file.pdf                      │
     *  │  API      →  GET /files/file.pdf  （無 Range）           │
     *  │  PDF Svr  ←  200 OK, Content-Length: N                  │
     *  │  Browser  ←  200 OK, body: 完整 N bytes                 │
     *  └─────────────────────────────────────────────────────────┘
     *
     *  ┌─ 中斷後續傳（帶 Range header）──────────────────────────┐
     *  │  Browser  →  GET /api/pdf/file.pdf, Range: bytes=X-     │
     *  │  API      →  GET /files/file.pdf,   Range: bytes=X-     │
     *  │  PDF Svr  ←  206 Partial Content                        │
     *  │              Content-Range: bytes X-(N-1)/N             │
     *  │              Content-Length: N-X                        │
     *  │  Browser  ←  206 Partial Content, body: 剩餘 N-X bytes  │
     *  └─────────────────────────────────────────────────────────┘
     *
     * @param range 前端傳入的 Range header，格式：bytes=起點-終點 或 bytes=起點-
     *              required=false 表示初次下載時不需要帶此 header
     */
    @GetMapping("/api/pdf/{filename}")
    public void proxyPdf(
            @PathVariable String filename,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletResponse response) throws IOException {

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            log.warn("Browser → PROXY   | 400 Bad Request — 路徑遍歷嘗試: {}", filename);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        URL url = new URL(PDF_SERVER_BASE + encoded);

        log.info("Browser → PROXY   | {} | Range: {}", filename, range != null ? range : "(none)");

        long startTs = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);

        if (range != null) {
            conn.setRequestProperty("Range", range);
        }

        log.info("PROXY  → PdfSvr  | GET {} | Range: {}", url, range != null ? range : "(none)");

        int status = conn.getResponseCode();
        response.setStatus(status);
        response.setContentType("application/pdf");

        String acceptRanges = conn.getHeaderField("Accept-Ranges");
        if (acceptRanges != null) {
            response.setHeader("Accept-Ranges", acceptRanges);
        }

        String contentRange = conn.getHeaderField("Content-Range");
        if (contentRange != null) {
            response.setHeader("Content-Range", contentRange);
        }

        String contentLength = conn.getHeaderField("Content-Length");
        if (contentLength != null) {
            response.setHeader("Content-Length", contentLength);
        }

        log.info("PdfSvr → PROXY   | status={} | Content-Length: {} | Content-Range: {} | Accept-Ranges: {}",
                status,
                contentLength != null ? contentLength : "—",
                contentRange != null ? contentRange : "—",
                acceptRanges != null ? acceptRanges : "—");

        byte[] body;
        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            body = in != null ? in.readAllBytes() : new byte[0];
        } finally {
            conn.disconnect();
        }

        long elapsed = System.currentTimeMillis() - startTs;
        log.info("PROXY  → Browser | 寫入記憶體完成 | {} bytes | 耗時 {} ms", body.length, elapsed);

        response.setHeader("Content-Length", String.valueOf(body.length));
        response.getOutputStream().write(body);
    }

    /**
     * 列出 file-storage/ 目錄中所有 PDF 檔案（供前端顯示選單用）。
     * 回傳每個檔案的名稱與大小，前端用大小來計算 Range 的起訖位置。
     */
    @GetMapping("/api/files")
    public List<Map<String, Object>> listFiles() {
        log.info("Browser → PROXY   | GET /api/files");
        File dir = new File("file-storage");
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[PROXY]  file-storage 目錄不存在");
            return List.of();
        }
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".pdf"));
        if (files == null) {
            return List.of();
        }
        List<Map<String, Object>> result = Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName))
                .map(f -> Map.<String, Object>of("name", f.getName(), "size", f.length()))
                .collect(Collectors.toList());
        log.info("PROXY → Browser | 回傳 {} 個 PDF 檔案", result.size());
        return result;
    }
}
