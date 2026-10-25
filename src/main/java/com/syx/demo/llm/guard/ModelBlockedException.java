package com.syx.demo.llm.guard;

/**
 * 熔断打开时抛出。上层（{@code ChatFacade}）捕获它后会<b>立刻换下一个候选模型</b>，
 * 不做任何重试 —— 熔断的意义就是快速失败，这时候重试只会把已经过载的下游压得更死。
 */
public class ModelBlockedException extends RuntimeException {

    private final String resource;

    public ModelBlockedException(String resource, String message, Throwable cause) {
        super(message, cause);
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }
}
