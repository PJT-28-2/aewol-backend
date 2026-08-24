package com.aewol.external.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SolapiSendResponse {

    private List<FailedMessage> failedMessageList;
    private GroupInfo groupInfo;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FailedMessage {
        private String statusCode;
        private String statusMessage;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupInfo {
        private Count count;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Count {
        private Integer registeredSuccess;
        private Integer registeredFailed;
    }
}
