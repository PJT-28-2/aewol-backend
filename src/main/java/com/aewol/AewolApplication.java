package com.aewol;

import com.aewol.config.MultipartLimits;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import java.io.File;

public class AewolApplication {

    public static void main(String[] args) throws Exception {
        // 활성 프로파일 설정 (기본값: local)
        String profile = System.getProperty("spring.profiles.active", "local");

        // Spring Application Context
        AnnotationConfigWebApplicationContext appCtx = new AnnotationConfigWebApplicationContext();
        appCtx.getEnvironment().setActiveProfiles(profile);
        appCtx.scan("com.aewol");

        // Embedded Tomcat 9 설정
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);
        tomcat.getConnector(); // 커넥터 초기화

        File baseDir = new File(System.getProperty("java.io.tmpdir"), "tomcat-aewol");
        baseDir.mkdirs();
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        Context context = tomcat.addContext("", baseDir.getAbsolutePath());

        // DispatcherServlet 등록
        DispatcherServlet dispatcher = new DispatcherServlet(appCtx);
        var servletWrapper = Tomcat.addServlet(context, "dispatcher", dispatcher);
        servletWrapper.setAsyncSupported(true);
        servletWrapper.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");

        // 멀티파트(파일 업로드) 요청 처리를 위한 설정. Spring Boot가 아니므로
        // application.yml의 spring.servlet.multipart.* 값은 자동 반영되지 않아 여기서 직접 설정해야
        // 한다 — 다만 숫자를 또 하드코딩하면 값이 다시 어긋날 수 있으므로(PR #197 리뷰) 실제
        // application.yml을 그대로 읽어오는 MultipartLimits를 거친다. 값을 바꿀 땐 application.yml만
        // 고치면 된다.
        long maxFileSize = MultipartLimits.maxFileSizeBytes();
        long maxRequestSize = MultipartLimits.maxRequestSizeBytes();
        servletWrapper.setMultipartConfigElement(new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), maxFileSize, maxRequestSize, 0));

        // CharacterEncoding 필터 (UTF-8)
        registerFilter(context, "encodingFilter",
                new CharacterEncodingFilter("UTF-8", true), "/*");

        // Spring Security 필터
        registerFilter(context, "springSecurityFilterChain",
                new DelegatingFilterProxy("springSecurityFilterChain"), "/*");

        tomcat.start();

        System.out.println("=====================================================");
        System.out.println(" 애월 서버 시작  ▶  http://localhost:8080");
        System.out.println(" 활성 프로파일   ▶  " + profile);
        System.out.println("=====================================================");

        tomcat.getServer().await();
    }

    private static void registerFilter(Context context, String name, Filter filter, String urlPattern) {
        FilterDef def = new FilterDef();
        def.setFilterName(name);
        def.setFilter(filter);
        context.addFilterDef(def);

        FilterMap map = new FilterMap();
        map.setFilterName(name);
        map.addURLPattern(urlPattern);
        context.addFilterMap(map);
    }
}
