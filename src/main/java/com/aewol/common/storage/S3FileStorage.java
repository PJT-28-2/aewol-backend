package com.aewol.common.storage;

import com.aewol.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3에 저장하는 운영용 구현.
 *
 * <p>로컬 디스크에 두면 인스턴스를 교체할 때 파일이 사라지고 서버를 늘릴 수도 없다.
 * 조회는 presigned URL로 브라우저가 S3에서 직접 받아가므로 파일 트래픽이 애플리케이션
 * 서버를 거치지 않는다.
 *
 * <p>자격증명은 따로 설정하지 않는다. 기본 제공자 체인이 환경변수 → 프로파일 →
 * 인스턴스 메타데이터 순으로 찾으므로 EC2에서는 인스턴스 프로파일(IAM Role)이 쓰인다.
 * 액세스 키를 파일이나 환경변수로 심지 않기 위한 선택이다.
 */
@Slf4j
@Component
@Profile("prod")
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    /** 공개 사본이 놓이는 prefix. 이 아래만 CDN이 서빙하도록 버킷 정책을 연다. */
    private final String publicPrefix;
    /** 공개 사본을 서빙하는 CDN 도메인. 비어 있으면 공개 기능을 끈 것으로 본다. */
    private final String publicBaseUrl;
    private final FileSignature signature;

    /**
     * 만료 시각이 같은 동안 재사용할 presigned URL 모음.
     *
     * <p>presigned URL에는 서명 시각이 들어 있어 호출할 때마다 문자열이 달라진다. 브라우저
     * 캐시는 URL을 키로 쓰므로 그대로 두면 목록을 열 때마다 이미지를 다시 받는다(캐릭터
     * 이미지가 장당 1~2MB다). {@link FileSignature#expiresAt()}이 만료 시각을 구간 단위로
     * 끊어 주므로, 같은 구간 안에서는 한 번 만든 URL을 그대로 재사용한다.
     *
     * <p>구간이 넘어가면 맵을 통째로 교체해 메모리가 한 구간분을 넘지 않게 한다. 교체가
     * 동시에 일어나면 한쪽 맵이 버려질 수 있지만, 버려진 항목은 다음 호출에서 다시
     * 만들어지므로 정확성에는 영향이 없다.
     */
    private final AtomicReference<UrlWindow> urlCache =
            new AtomicReference<>(new UrlWindow(0L, Map.of()));

    private record UrlWindow(long expiresAt, Map<String, String> urls) {
    }

    @Autowired
    public S3FileStorage(@Value("${file.s3.bucket}") String bucket,
                         @Value("${file.s3.region:ap-northeast-2}") String region,
                         @Value("${file.s3.public-prefix:public}") String publicPrefix,
                         @Value("${file.s3.public-base-url:}") String publicBaseUrl,
                         FileSignature signature) {
        this(S3Client.builder().region(Region.of(region)).build(),
                S3Presigner.builder().region(Region.of(region)).build(),
                bucket, publicPrefix, publicBaseUrl, signature);
        if (StringUtils.hasText(publicBaseUrl)) {
            log.info("S3 파일 저장소 사용 - bucket: {}, region: {}, 공개 서빙: {}",
                    bucket, region, publicBaseUrl);
        } else {
            // CDN이 없어도 멍스타그램은 서명 URL로 보여 준다. 만료 없는 주소를 쓰려면
            // S3_PUBLIC_BASE_URL을 넣으면 된다.
            log.warn("S3 파일 저장소 사용 - bucket: {}, region: {}. "
                    + "[PUBLIC_SERVING_DISABLED] S3_PUBLIC_BASE_URL이 비어 있어 공개 CDN 사본을 만들지 않는다. "
                    + "멍스타그램은 서명 URL로 사진을 보여 준다.",
                    bucket, region);
        }
    }

    /** 테스트에서 S3 클라이언트를 직접 주입하기 위한 생성자다. */
    S3FileStorage(S3Client s3, S3Presigner presigner, String bucket,
                  String publicPrefix, String publicBaseUrl, FileSignature signature) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.publicPrefix = trimSlashes(publicPrefix);
        // 끝 슬래시가 있으면 주소에 //가 생긴다.
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
        this.signature = signature;
    }

    private static String trimSlashes(String value) {
        return value == null ? "public" : value.replaceAll("^/+|/+$", "");
    }

    @Override
    public String store(byte[] content, String directory, String extension) {
        String key = directory + "/" + UUID.randomUUID() + "." + extension;
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            // S3는 업로드 시 지정한 Content-Type을 그대로 응답에 실어 준다.
                            // 지정하지 않으면 octet-stream이 되어 브라우저가 이미지를
                            // 표시하지 않고 내려받아 버린다.
                            .contentType(FileMediaTypes.of(key).toString())
                            .build(),
                    RequestBody.fromBytes(content));
            return key;
        } catch (SdkException e) {
            log.error("[FILE_STORE_FAILED] S3 저장 실패 - key: {}, {}바이트", key, content.length, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "파일을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    @Override
    public void delete(String key) {
        // 로컬 구현과 같은 정책이다. 이전 파일 정리는 부가 작업이라 실패해도
        // 본 작업을 깨뜨리지 않는다.
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(normalize(key))
                    .build());
        } catch (RuntimeException e) {
            log.warn("[FILE_DELETE_FAILED] S3 삭제 실패 - key: {}", key, e);
        }
    }

    /**
     * 원본을 공개 prefix로 복사한다. 원본은 그대로 둔다.
     *
     * <p>공개 키에 UUID를 새로 쓰는 이유는 원본 키에서 유추할 수 없게 하려는 것이다.
     * 규칙적이면 비공개 일기의 키를 추측해 공개 주소를 맞혀볼 수 있다.
     */
    @Override
    public String publish(String key) {
        if (!StringUtils.hasText(publicBaseUrl)) {
            log.warn("[FILE_PUBLISH_DISABLED] 공개 CDN 주소가 설정되지 않아 공개 사본을 만들지 않는다");
            return null;
        }
        String source = normalize(key);
        String publicKey = publicPrefix + "/" + UUID.randomUUID() + extractExtension(source);
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(source)
                    .destinationBucket(bucket)
                    .destinationKey(publicKey)
                    .build());
            return publicKey;
        } catch (SdkException e) {
            log.error("[FILE_PUBLISH_FAILED] 공개 사본 복사 실패 - key: {}", key, e);
            return null;
        }
    }

    @Override
    public String createPublicKey(String key) {
        if (!StringUtils.hasText(publicBaseUrl)) {
            log.warn("[FILE_PUBLISH_DISABLED] public base URL is empty, public copy cannot be reserved");
            return null;
        }
        String source = normalize(key);
        return publicPrefix + "/" + UUID.randomUUID() + extractExtension(source);
    }

    /** 마지막 '/' 이후 구간에서만 확장자를 찾는다. 디렉터리 이름에 점이 있어도 안전하다. */
    private static String extractExtension(String path) {
        int lastSlash = path.lastIndexOf('/');
        int lastDot = path.lastIndexOf('.');
        return lastDot > lastSlash ? path.substring(lastDot) : "";
    }

    @Override
    public boolean publish(String key, String publicKey) {
        if (!StringUtils.hasText(publicBaseUrl) || !StringUtils.hasText(publicKey)) {
            log.warn("[FILE_PUBLISH_DISABLED] public base URL or public key is empty");
            return false;
        }
        String source = normalize(key);
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(source)
                    .destinationBucket(bucket)
                    .destinationKey(normalize(publicKey))
                    .build());
            return true;
        } catch (SdkException e) {
            log.error("[FILE_PUBLISH_FAILED] public copy failed - key: {}, publicKey: {}", key, publicKey, e);
            return false;
        }
    }

    @Override
    public void unpublish(String publicKey) {
        // delete와 같은 계약이다. 비공개로 되돌리는 것이 사본 삭제 실패로 막히면 안 된다.
        if (!StringUtils.hasText(publicKey)) {
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(normalize(publicKey))
                    .build());
        } catch (RuntimeException e) {
            log.warn("[FILE_UNPUBLISH_FAILED] 공개 사본 삭제 실패 - key: {}", publicKey, e);
        }
    }

    @Override
    public String publicUrl(String publicKey) {
        if (!StringUtils.hasText(publicKey) || !StringUtils.hasText(publicBaseUrl)) {
            return null;
        }
        return publicBaseUrl + "/" + normalize(publicKey);
    }

    @Override
    public boolean isPublicServingEnabled() {
        return StringUtils.hasText(publicBaseUrl);
    }

    @Override
    public InputStream read(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(normalize(key))
                    .build());
        } catch (NoSuchKeyException e) {
            throw BusinessException.notFound("파일을 찾을 수 없습니다.");
        } catch (SdkException e) {
            log.error("[FILE_READ_FAILED] S3 읽기 실패 - key: {}", key, e);
            throw BusinessException.notFound("파일을 찾을 수 없습니다.");
        }
    }

    @Override
    public String signedUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = normalize(key);
        long expiresAt = signature.expiresAt();

        UrlWindow window = urlCache.get();
        if (window.expiresAt() != expiresAt) {
            window = new UrlWindow(expiresAt, new ConcurrentHashMap<>());
            urlCache.set(window);
        }
        return window.urls().computeIfAbsent(normalizedKey, k -> presign(k, expiresAt));
    }

    private String presign(String key, long expiresAt) {
        Duration ttl = Duration.ofSeconds(Math.max(1, expiresAt - Instant.now().getEpochSecond()));
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build())
                        .build())
                .url()
                .toString();
    }

    /**
     * 로컬 저장소를 쓰던 시기에 저장된 값은 {@code /uploads/diary/a.png}처럼 URL 형태였다.
     * S3 키에는 앞의 슬래시가 들어가면 안 되므로 접두사를 떼어 맞춘다.
     */
    private String normalize(String key) {
        if (key != null && key.startsWith("/uploads/")) {
            return key.substring("/uploads/".length());
        }
        return key;
    }
}
