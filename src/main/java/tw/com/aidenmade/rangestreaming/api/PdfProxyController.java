package tw.com.aidenmade.rangestreaming.api;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private static final String PROXY_PDF_PATH = "/api/pdf/{filename}";
    private static final String PROXY_FILES_PATH = "/api/files";
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
    @GetMapping(PROXY_PDF_PATH)
    public void proxyPdf(
            @PathVariable String filename,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
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
        // 連線逾時要短：PDF Server 不可達時快速失敗，避免 API 請求長時間卡住。
        conn.setConnectTimeout(5000);
        // 讀取逾時稍長：允許大檔或慢網路持續串流，但仍保留上限避免無限等待。
        conn.setReadTimeout(30000);

        if (range != null) {
            // 將前端的 Range 原樣轉發到 PDF Server，讓上游決定是否回傳 206 區段內容。
            conn.setRequestProperty(HttpHeaders.RANGE, range);
        }

        log.info("PROXY  → PdfSvr  | GET {} | Range: {}", url, range != null ? range : "(none)");

        int status = conn.getResponseCode();
        // Accept-Ranges（可接受的範圍）：伺服器可接受的 Range 單位（例如：bytes）。
        String acceptRanges = conn.getHeaderField(HttpHeaders.ACCEPT_RANGES);
        // Content-Range（內容範圍）：本次回應內容在整體檔案中的位元組區間（例如：bytes 1000-1999/5000）。
        String contentRange = conn.getHeaderField(HttpHeaders.CONTENT_RANGE);
        // Content-Length（內容長度）：本次回應 body 的位元組長度（例如：1000）。
        String contentLength = conn.getHeaderField(HttpHeaders.CONTENT_LENGTH);

        log.info("PdfSvr → PROXY   | status={} | Content-Length: {} | Content-Range: {} | Accept-Ranges: {}",
                status,
                contentLength != null ? contentLength : "—",
                contentRange != null ? contentRange : "—",
                acceptRanges != null ? acceptRanges : "—");

        byte[] body;
        // 4xx/5xx 讀 error stream、其餘讀正常 stream，確保不漏掉上游回應內容。
        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            // 可能遇到無 body 回應（in 為 null），此時以空陣列代表。
            body = in != null ? in.readAllBytes() : new byte[0];
        } finally {
            // 無論成功或失敗都主動釋放 HttpURLConnection 資源。
            conn.disconnect();
        }

        long elapsed = System.currentTimeMillis() - startTs;
        log.info("PROXY  → Browser | 寫入記憶體完成 | {} bytes | 耗時 {} ms", body.length, elapsed);

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);

        // 上游若忽略 Range 並回 200，代理端仍維持續傳語意：本地切片後回 206。
        if (range != null && status == HttpServletResponse.SC_OK) {
            int[] bounds = parseRange(range, body.length);
            // parseRange 回 null 代表 Range 格式錯誤或請求範圍超出檔案可滿足區間。
            if (bounds == null) {
                log.warn("PROXY  → Browser | 416 Range Not Satisfiable | Range={} | total={}", range, body.length);
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                // 告知客戶端伺服器支援 bytes，但本次請求無法滿足；*/total 表示可用總長度。
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + body.length);
                // 416 回應不回傳檔案內容，因此 body 長度為 0。
                response.setHeader(HttpHeaders.CONTENT_LENGTH, "0");
                return;
            }

            // 由解析結果取得要回傳的區段起訖，length 為本次片段實際大小。
            int start = bounds[0];
            int end = bounds[1];
            int length = end - start + 1;

            log.info("PROXY  → Browser | fallback 206 | bytes {}-{}/{} ({} bytes)", start, end, body.length, length);
            // 以 206 + Range 相關標頭回覆，維持前端續傳所需的 HTTP 語意。
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + body.length);
            response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(length));
            // 只寫出 body 指定區段（start 起算、共 length bytes）。
            response.getOutputStream().write(body, start, length);
            return;
        }

        response.setStatus(status);
        if (acceptRanges != null) {
            // 回傳上游的可續傳能力（通常是 bytes），讓前端知道可用 Range 續傳。
            response.setHeader(HttpHeaders.ACCEPT_RANGES, acceptRanges);
        }
        if (contentRange != null) {
            // 續傳時把實際區段範圍透傳給前端（例如 bytes 1000-1999/5000）。
            response.setHeader(HttpHeaders.CONTENT_RANGE, contentRange);
        }
        if (contentLength != null) {
            // 透傳本次回應 body 長度；206 時是片段長度，200 時通常是整檔長度。
            response.setHeader(HttpHeaders.CONTENT_LENGTH, contentLength);
        }

        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));
        response.getOutputStream().write(body);
    }

    static int[] parseRange(String rangeHeader, int total) {
        if (rangeHeader == null || total <= 0 || !rangeHeader.startsWith("bytes=") || rangeHeader.contains(",")) {
            return null;
        }

        String spec = rangeHeader.substring("bytes=".length());
        String[] parts = spec.split("-", 2);
        if (parts.length != 2 || (parts[0].isEmpty() && parts[1].isEmpty())) {
            return null;
        }

        try {
            int start;
            int end;
            if (parts[0].isEmpty()) {
                int suffix = Integer.parseInt(parts[1]);
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else if (parts[1].isEmpty()) {
                start = Integer.parseInt(parts[0]);
                end = total - 1;
            } else {
                start = Integer.parseInt(parts[0]);
                end = Integer.parseInt(parts[1]);
            }

            if (start < 0 || start >= total || end < start) {
                return null;
            }
            end = Math.min(end, total - 1);
            return new int[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 列出 file-storage/ 目錄中所有 PDF 檔案（供前端顯示選單用）。
     * 回傳每個檔案的名稱與大小，前端用大小來計算 Range 的起訖位置。
     */
    @GetMapping(PROXY_FILES_PATH)
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
