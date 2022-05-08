package com.simplefanc.voj.judger.common.exception;

import lombok.Data;

/**
 * @Author: chenfan
 * @Date: 2021/1/31 00:16
 * @Description:
 */
@Data
public class RuntimeError extends Exception {

    private String stdout;

    private String stderr;

    public RuntimeError(String message, String stdout, String stderr) {
        super(message);
        this.stdout = stdout;
        this.stderr = stderr;
    }

}