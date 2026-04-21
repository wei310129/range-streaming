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

        // 防止路徑遍歷攻擊（Path Traversal）
        // 若允許 "../" 這類字元，惡意請求可能讀取 file-storage 以外的系統檔案
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // URL 編碼中文或特殊字元的檔名
        // 中文在 URL 中必須轉成 %XX 格式（如「實」→ %E5%AF%A6）
        // replace("+", "%20")：URLEncoder 把空格編成 +，但 URL path 中空格應為 %20
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        URL url = new URL(PDF_SERVER_BASE + encoded);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);

        // ★ Range 轉傳：這是代理的核心
        // 前端帶來的 Range header 必須原封不動地傳給 PDF Server，
        // PDF Server 才會知道要回傳哪個 byte 區段
        if (range != null) {
            conn.setRequestProperty("Range", range);
        }

        // 從 PDF Server 取得回應狀態碼並原樣回傳給前端
        // 完整下載時 PDF Server 回傳 200，Range 請求時回傳 206
        int status = conn.getResponseCode();
        response.setStatus(status);
        response.setContentType("application/pdf");

        // ★ 告知前端此資源支援 Range 請求（斷點續傳的前提）
        // 前端看到這個 header 才知道可以用 Range: bytes=X- 來續傳
        response.setHeader("Accept-Ranges", "bytes");

        // ★ 轉傳 Content-Range header（206 回應時必須有）
        // 格式：bytes 起點-終點/總大小，例如：bytes 1024-2047/10240
        // 前端靠這個 header 確認「收到的是哪一段」
        String contentRange = conn.getHeaderField("Content-Range");
        if (contentRange != null) {
            response.setHeader("Content-Range", contentRange);
        }

        // 轉傳 Content-Length，讓前端知道這次回應的 body 有多少 bytes
        // 完整下載時 = 整個檔案大小；Range 請求時 = 此段的大小
        String contentLength = conn.getHeaderField("Content-Length");
        if (contentLength != null) {
            response.setHeader("Content-Length", contentLength);
        }

        // ★ Streaming（串流）傳輸：邊讀邊寫，不把整個檔案載入記憶體
        // transferTo() 內部使用固定大小的 buffer 循環讀寫，
        // 無論檔案多大，記憶體用量都是固定的（約 8KB buffer）
        // status >= 400 時改用 getErrorStream()，避免 getInputStream() 拋出例外
        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
             OutputStream out = response.getOutputStream()) {
            if (in != null) {
                in.transferTo(out);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 列出 file-storage/ 目錄中所有 PDF 檔案（供前端顯示選單用）。
     * 回傳每個檔案的名稱與大小，前端用大小來計算 Range 的起訖位置。
     */
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
