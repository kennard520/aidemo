package org.ai.aidemo.controller;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RequestVo {
    @NotBlank
    private String query;
    private String sessionId;
    private ToolCallVo toolCallVo=new ToolCallVo();
    private boolean isToolCallInProgress;
    private boolean isToolCallFinished;

    @Data
    public static class ToolCallVo{
        private String toolCallId;
        private String functionName;
        private StringBuilder toolArguments=new StringBuilder();
        private Object toolCallResult;
    }
}
