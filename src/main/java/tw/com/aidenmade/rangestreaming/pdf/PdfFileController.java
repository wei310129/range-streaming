package tw.com.aidenmade.rangestreaming.pdf;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * PDF Server 的 REST API。
 *
 * 取代原本的 ResourceHttpRequestHandler 靜態資源路由，
 * 由此 Controller 自行解析 Range header 並從記憶體回傳對應區段。
 */
@RestController
public class PdfFileController {

    private static final Logger log = LoggerFactory.getLogger(PdfFileController.class);

    private final PdfFileStore store;
    private final boolean rangeEnabled;

    public PdfFileController(PdfFileStore store,
                             @Value("${pdf.range-enabled:true}") boolean rangeEnabled) {
        this.store = store;
        this.rangeEnabled = rangeEnabled;
    }

    /**
     * 提供 PDF 檔案，支援 HTTP Range Request。
     *
     * Range header 格式：
     *   bytes=500-999   → 回傳 byte 500 到 999（共 500 bytes）
     *   bytes=500-      → 回傳 byte 500 到 EOF
     *   bytes=-500      → 回傳最後 500 bytes
     *
     * 無 Range → 200 OK + 完整檔案
     * 有 Range → 206 Partial Content + Content-Range
     */
    @GetMapping("/files/{filename}")
    public void serveFile(
            @PathVariable String filename,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletResponse response) throws IOException {

        byte[] content = store.get(filename);
        if (content == null) {
            log.warn("[PDF  ] 404 找不到: {}", filename);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        response.setContentType("application/pdf");
        if (rangeEnabled) {
            response.setHeader("Accept-Ranges", "bytes");
        }

        // 關閉 Range 時一律回完整檔案，即使客戶端有帶 Range header。
        if (!rangeEnabled || range == null) {
            log.info("[PDF  →] 200 OK | {} | {} bytes | rangeEnabled={} | requestRange={}",
                    filename,
                    content.length,
                    rangeEnabled,
                    range != null ? range : "(none)");
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Content-Length", String.valueOf(content.length));
            response.getOutputStream().write(content);
            return;
        }

        // ── Range 請求 ──
        int[] bounds = parseRange(range, content.length);
        if (bounds == null) {
            log.warn("[PDF  ] 416 Range 格式錯誤: {}", range);
            response.setStatus(416); // Range Not Satisfiable
            response.setHeader("Content-Range", "bytes */" + content.length);
            return;
        }

        int start  = bounds[0];
        int end    = bounds[1];
        int length = end - start + 1;

        log.info("[PDF  →] 206 Partial Content | {} | bytes {}-{}/{} ({} bytes)",
                filename, start, end, content.length, length);

        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader("Content-Range",  "bytes " + start + "-" + end + "/" + content.length);
        response.setHeader("Content-Length", String.valueOf(length));
        response.getOutputStream().write(content, start, length);
    }

    /**
     * 解析 Range header，回傳 [start, end]（inclusive，已夾在合法範圍內）。
     * 格式非法時回傳 null。
     */
    private int[] parseRange(String rangeHeader, int total) {
        if (!rangeHeader.startsWith("bytes=")) return null;

        String spec   = rangeHeader.substring("bytes=".length());
        String[] parts = spec.split("-", 2);
        if (parts.length != 2) return null;

        try {
            int start, end;
            if (parts[0].isEmpty()) {
                // bytes=-500：最後 N bytes
                int suffix = Integer.parseInt(parts[1]);
                start = Math.max(0, total - suffix);
                end   = total - 1;
            } else if (parts[1].isEmpty()) {
                // bytes=500-：到 EOF
                start = Integer.parseInt(parts[0]);
                end   = total - 1;
            } else {
                start = Integer.parseInt(parts[0]);
                end   = Integer.parseInt(parts[1]);
            }

            if (start < 0 || start >= total || end < start) return null;
            end = Math.min(end, total - 1);
            return new int[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
