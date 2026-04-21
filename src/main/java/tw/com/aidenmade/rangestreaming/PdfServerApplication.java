package tw.com.aidenmade.rangestreaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PDF Server 的啟動進入點，監聽 8081 port。
 *
 * 架構中的角色：
 *   Browser → API Server(:8080) → [PDF Server(:8081)] → file-storage/
 *
 * 與 RangeStreamingApplication（API Server）分開啟動，
 * 目的是模擬真實場景中「檔案儲存服務」與「API 服務」分離的部署方式。
 *
 * 啟動指令：./mvnw spring-boot:run -Ppdf-server
 */
@SpringBootApplication(
    // 只掃描 pdf 子套件，避免載入 API Server 的 Controller（PdfProxyController）
    // 若不限制，兩個 Application 會互相干擾，載入對方的 Bean
    scanBasePackages = "tw.com.aidenmade.rangestreaming.pdf"
)
public class PdfServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PdfServerApplication.class);

        // 啟用 "pdf" profile，讓 Spring Boot 載入 application-pdf.yaml
        // application-pdf.yaml 裡設定了 server.port=8081，覆蓋預設的 8080
        app.setAdditionalProfiles("pdf");
        app.run(args);
    }
}
