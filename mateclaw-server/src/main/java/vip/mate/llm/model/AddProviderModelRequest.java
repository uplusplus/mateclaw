package vip.mate.llm.model;

import lombok.Data;

@Data
public class AddProviderModelRequest {
    private String id;
    private String name;
    /** Optional per-model input context window override. Null/<=0 = use global default. */
    private Integer maxInputTokens;
}
