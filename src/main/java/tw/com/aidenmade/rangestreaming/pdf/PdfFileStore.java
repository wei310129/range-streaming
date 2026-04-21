package tw.com.aidenmade.rangestreaming.pdf;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PDF 檔案的記憶體儲存。
 *
 * 不落地原則：PDF bytes 在啟動時一次性載入記憶體，
 * 後續所有請求直接從 Map 讀取，不再碰磁碟。
 *
 * 真實場景可將 @PostConstruct 改為從 S3、DB BLOB 或其他來源載入。
 */
@Component
public class PdfFileStore {

    private static final Logger log = LoggerFactory.getLogger(PdfFileStore.class);

    // filename → PDF bytes（唯讀，啟動後不修改）
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @PostConstruct
    public void load() {
        File dir = new File("file-storage");
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[STORE] file-storage 目錄不存在，記憶體中無任何 PDF");
            return;
        }

        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".pdf"));
        if (files == null || files.length == 0) {
            log.warn("[STORE] file-storage 內無 PDF 檔案");
            return;
        }

        for (File f : files) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                store.put(f.getName(), bytes);
                log.info("[STORE] 載入 {} ({} bytes)", f.getName(), bytes.length);
            } catch (IOException e) {
                log.error("[STORE] 載入失敗: {}", f.getName(), e);
            }
        }

        log.info("[STORE] 共載入 {} 個 PDF，總計 {} bytes",
                store.size(),
                store.values().stream().mapToLong(b -> b.length).sum());
    }

    public byte[] get(String filename) {
        return store.get(filename);
    }

    public Set<String> filenames() {
        return Collections.unmodifiableSet(store.keySet());
    }
}
