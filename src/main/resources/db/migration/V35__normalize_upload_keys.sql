-- 업로드 파일 저장소를 S3로 옮기면서(#198, #203) DB에 저장하는 값을 URL이 아닌
-- 저장 키로 통일한다.
--
-- 예전 코드(FileUtil, ClaimServiceImpl)는 "/uploads/group-purchase/a.png" 같은 URL을
-- 넣었지만, FileStorage는 "group-purchase/a.png" 형태의 키를 쓴다. 키에는 앞의
-- 슬래시가 들어가면 안 되므로("/uploads/" 접두사가 S3 키의 일부가 되어 버린다)
-- 기존 값을 잘라 맞춘다. "/uploads/"는 9자라 10번째 문자부터가 키다.
--
-- 조회 쪽(FileStorage.signedUrl)은 접두사가 남아 있어도 정규화하므로, 이 마이그레이션이
-- 늦게 적용돼도 화면이 깨지지는 않는다. 저장값 형식을 하나로 맞추는 것이 목적이다.

UPDATE `group_purchase`
   SET `image` = SUBSTRING(`image`, 10)
 WHERE `image` LIKE '/uploads/%';

UPDATE `inquiry_attachment`
   SET `file_url` = SUBSTRING(`file_url`, 10)
 WHERE `file_url` LIKE '/uploads/%';

UPDATE `pet_document`
   SET `file_url` = SUBSTRING(`file_url`, 10)
 WHERE `file_url` LIKE '/uploads/%';

UPDATE `insurance_claim`
   SET `receipt_image_url` = SUBSTRING(`receipt_image_url`, 10)
 WHERE `receipt_image_url` LIKE '/uploads/%';
