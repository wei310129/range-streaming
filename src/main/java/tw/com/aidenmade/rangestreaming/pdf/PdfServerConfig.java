package tw.com.aidenmade.rangestreaming.pdf;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * PDF Server 的靜態資源設定。
 *
 * 核心概念：Spring 的 ResourceHttpRequestHandler 內建支援 HTTP Range Request。
 * 只要透過 addResourceHandlers 註冊資料夾，不需要自己寫任何 Range 解析邏輯，
 * Spring 就會自動處理以下行為：
 *
 *   1. 在回應中加上 Accept-Ranges: bytes，告知客戶端「此資源支援 Range 請求」
 *   2. 收到 Range: bytes=X-Y 時，只讀取並回傳檔案的指定區段
 *   3. 回傳狀態碼 206 Partial Content，並附上 Content-Range: bytes X-Y/Total
 *   4. 若無 Range header，則正常回傳完整檔案（200 OK）
 */
@Configuration
public class PdfServerConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            // 將所有 /files/** 的 HTTP 請求對應到本地資料夾
            .addResourceHandler("/files/**")
            // file: 前綴代表本地檔案系統路徑，. 代表 JVM 工作目錄（專案根目錄）
            // 實際對應路徑：{專案根目錄}/file-storage/
            .addResourceLocations("file:./file-storage/")
            // 關閉 HTTP 快取，方便開發測試時立即反映檔案變動
            // 正式環境可設為較大的值（單位：秒）以提升效能
            .setCachePeriod(0);
    }
}
