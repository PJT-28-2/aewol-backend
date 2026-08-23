package com.aewol;

import com.aewol.config.MultipartLimits;
import com.aewol.config.RequestIdFilter;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.File;
import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;

public class AewolApplication {

    /**
     * JUL로 나가는 로그를 slf4j로 넘긴다.
     *
     * <p>jul-to-slf4j는 클래스패스에 두는 것만으로는 아무 일도 하지 않는다. 이 호출이
     * 없으면 톰캣 내부 로그가 logback.xml을 거치지 않고 JUL 기본 설정대로 따로 찍혀,
     * 요청 추적 id도 붙지 않고 레벨 조정도 먹지 않는다.
     *
     * <p>기존 핸들러를 먼저 지우지 않으면 같은 로그가 두 번 찍힌다.
     */
    private static void installJulBridge() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    public static void main(String[] args) throws Exception {
        installJulBridge();

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

        // 요청 추적 id는 보안 필터보다 먼저 붙인다. 인증에서 튕겨 나가는 요청의 로그도
        // 같은 id로 묶여야 "누가 왜 401을 받았는지"를 되짚을 수 있다.
        registerFilter(context, "requestIdFilter", new RequestIdFilter(), "/*");

        // 요청 소요 시간은 보안 필터보다 바깥에서 잰다. 인증에서 튕겨 나가는 요청도
        // 서버가 쓴 시간이고, 401이 몰리는 상황 자체가 봐야 할 신호다.
        registerFilter(context, "httpMetricsFilter",
                new DelegatingFilterProxy("httpMetricsFilter"), "/*");

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
