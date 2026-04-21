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
     * pdf.range-enabled=false → 忽略 Range，固定回 200 + 完整檔案
     * pdf.range-enabled=true  → 無 Range 回 200；有 Range 回 206 + Content-Range
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
     * 解析單一 Range header，回傳 [start, end]（兩端都包含，inclusive）。
     *
     * 支援格式：
     *   1) bytes=500-999  -> 明確起訖
     *   2) bytes=500-     -> 從 500 到檔案結尾
     *   3) bytes=-500     -> 最後 500 bytes
     *
     * 回傳 null 代表「格式錯誤或範圍不可滿足」，例如：
     *   - 不是 bytes= 開頭
     *   - 缺少 '-' 分隔
     *   - 數字解析失敗
     *   - start 超出檔案大小、或 end < start
     *
     * total 參數用途：
     *   - 代表「完整檔案總長度（bytes）」；呼叫端傳入的是 content.length
     *   - 用來計算 EOF（total - 1）、suffix 起點、以及範圍合法性檢查
     *
     * 例子（total = 10，合法 byte 索引為 0..9）：
     *   - bytes=2-   -> [2, 9]
     *   - bytes=-3   -> [7, 9]
     *   - bytes=2-20 -> [2, 9]（end 會被夾到 total - 1）
     */
    private int[] parseRange(String rangeHeader, int total) {
        // 只接受 bytes 單位；其他單位直接視為無效。
        if (!rangeHeader.startsWith("bytes=")) return null;

        // 拿掉 "bytes=" 後只保留區間規格，例如 "500-"、"-500"、"500-999"。
        String spec   = rangeHeader.substring("bytes=".length());
        String[] parts = spec.split("-", 2);
        // 必須剛好能拆成 [左邊, 右邊] 兩段。
        if (parts.length != 2) return null;

        try {
            int start, end;
            if (parts[0].isEmpty()) {
                // bytes=-N：取最後 N bytes。
                int suffix = Integer.parseInt(parts[1]);
                start = Math.max(0, total - suffix);
                end   = total - 1;
            } else if (parts[1].isEmpty()) {
                // bytes=X-：從 X 取到 EOF。
                start = Integer.parseInt(parts[0]);
                end   = total - 1;
            } else {
                // bytes=X-Y：明確起訖。
                start = Integer.parseInt(parts[0]);
                end   = Integer.parseInt(parts[1]);
            }

            // start 必須落在檔案內，且 end 不能小於 start。
            if (start < 0 || start >= total || end < start) return null;
            // 若 end 超過檔案尾端，夾到最後一個合法 byte。
            end = Math.min(end, total - 1);
            return new int[]{start, end};
        } catch (NumberFormatException e) {
            // 非數字（例如 bytes=a-b）視為格式錯誤。
            return null;
        }
    }
}
