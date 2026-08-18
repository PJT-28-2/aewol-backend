package com.aewol;

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
        // application.yml의 spring.servlet.multipart.* 값은 자동 반영되지 않아 여기서 직접 설정한다.
        // 1:1 문의 첨부파일이 파일당 최대 10MB, 최대 3개까지 허용되어(api_명세서.md) 전체 요청
        // 크기가 10MB를 넘을 수 있다 — application.yml의 max-request-size(35MB)와 값을 맞춘다.
        long maxFileSize = 10L * 1024 * 1024;   // application.yml: max-file-size
        long maxRequestSize = 35L * 1024 * 1024; // application.yml: max-request-size
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
