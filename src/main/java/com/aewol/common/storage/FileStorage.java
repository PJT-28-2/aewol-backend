package com.aewol.common.storage;

import java.io.InputStream;

/**
 * 업로드·생성 파일의 저장 위치를 감춘다.
 *
 * <p>DB에는 URL이 아니라 이 인터페이스가 돌려주는 <b>키</b>를 저장한다.
 * ({@code diary/abc.png}) URL을 그대로 넣어 두면 저장 위치를 옮길 때 데이터를
 * 통째로 마이그레이션해야 한다.
 *
 * <p>화면에 보여줄 주소는 {@link #signedUrl(String)}으로 조회 시점에 만든다.
 * 나중에 S3로 옮기더라도 이 인터페이스의 구현체만 바꾸면 되고, 도메인 코드와
 * 저장된 데이터는 손대지 않아도 된다.
 */
public interface FileStorage {

    /**
     * @param directory 논리 폴더 (예: {@code diary})
     * @return 저장 키 (예: {@code diary/8f0c-....png})
     */
    String store(byte[] content, String directory, String extension);

    /**
     * 저장된 파일을 지운다.
     *
     * <p><b>계약: 구현체는 예외를 던지지 않는다.</b> 실패는 구현체 내부에서 로그만 남기고
     * 삼켜야 한다. 업로드 롤백처럼 "정리가 실패해도 원래 예외를 가리면 안 되는" 호출부가
     * 안전하게 fire-and-forget으로 쓸 수 있어야 하기 때문이다(PR #200 리뷰). {@link LocalFileStorage}는
     * 이 계약을 따른다 — 새 구현체를 추가할 때도 반드시 지킬 것.
     */
    void delete(String key);

    InputStream read(String key);

    /**
     * 브라우저가 바로 열 수 있는 임시 주소를 만든다.
     *
     * <p>{@code <img>} 태그는 Authorization 헤더를 붙일 수 없다. 그래서 인증 대신
     * 서명과 만료 시각을 URL에 실어 보낸다. S3 presigned URL과 같은 방식이라
     * 나중에 갈아탈 때 호출부가 바뀌지 않는다.
     */
    String signedUrl(String key);
}
