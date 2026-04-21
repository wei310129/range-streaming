package tw.com.aidenmade.rangestreaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "tw.com.aidenmade.rangestreaming.api")
public class RangeStreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RangeStreamingApplication.class, args);
    }

}
