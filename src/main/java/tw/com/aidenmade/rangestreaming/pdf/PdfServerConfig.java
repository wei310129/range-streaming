package tw.com.aidenmade.rangestreaming.pdf;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * PDF Server 的 MVC 設定。
 *
 * 改為由 PdfFileController 自行處理 /files/** 請求（不落地原則），
 * 此處只保留 logging interceptor 的註冊。
 */
@Configuration
public class PdfServerConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PdfLoggingInterceptor()).addPathPatterns("/files/**");
    }
}
