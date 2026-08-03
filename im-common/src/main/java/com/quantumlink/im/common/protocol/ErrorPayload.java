package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 错误体(服务端 → 客户端,ERROR 帧)。
 */
@Getter
@Setter
public class ErrorPayload {
    private String code;
    private String message;
}
