package tw.com.aidenmade.rangestreaming.pdf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

public class PdfLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PdfLoggingInterceptor.class);
    private static final String ATTR_START = "_startTs";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(ATTR_START, System.currentTimeMillis());
        String range = req.getHeader(HttpHeaders.RANGE);
        log.info("PROXY → PdfSvr  | {} {} | Range: {}", req.getMethod(), req.getRequestURI(),
                range != null ? range : "(none)");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        Long startTs = (Long) req.getAttribute(ATTR_START);
        long elapsed = startTs != null ? System.currentTimeMillis() - startTs : -1;
        String contentRange  = res.getHeader(HttpHeaders.CONTENT_RANGE);
        String contentLength = res.getHeader(HttpHeaders.CONTENT_LENGTH);
        String acceptRanges  = res.getHeader(HttpHeaders.ACCEPT_RANGES);

        log.info("PdfSvr → PROXY   | {} {} | status={} | Accept-Ranges={} | Content-Length={} | Content-Range={} | {}ms",
                req.getMethod(), req.getRequestURI(),
                res.getStatus(),
                acceptRanges  != null ? acceptRanges  : "—",
                contentLength != null ? contentLength : "—",
                contentRange  != null ? contentRange  : "—",
                elapsed);

        if (ex != null) {
            log.error("PdfSvr → PROXY   | 傳輸異常: {}", ex.getMessage(), ex);
        }
    }
}
