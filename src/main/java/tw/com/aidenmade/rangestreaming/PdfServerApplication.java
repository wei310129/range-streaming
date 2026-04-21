package tw.com.aidenmade.rangestreaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "tw.com.aidenmade.rangestreaming.pdf")
public class PdfServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PdfServerApplication.class);
        app.setAdditionalProfiles("pdf");
        app.run(args);
    }
}
