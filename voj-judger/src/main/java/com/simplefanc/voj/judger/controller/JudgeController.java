package com.simplefanc.voj.judger.controller;

import com.simplefanc.voj.common.pojo.dto.CompileDTO;
import com.simplefanc.voj.common.pojo.dto.JudgeDTO;
import com.simplefanc.voj.common.pojo.entity.judge.Judge;
import com.simplefanc.voj.common.result.CommonResult;
import com.simplefanc.voj.common.result.ResultStatus;
import com.simplefanc.voj.judger.common.exception.SystemException;
import com.simplefanc.voj.judger.service.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: chenfan
 * @Date: 2021/10/29 22:22
 * @Description: 处理代码提交
 */
@RestController
@RefreshScope
@RequiredArgsConstructor
public class JudgeController {

    private final JudgeService judgeService;

    @Value("${voj.judge.token}")
    private String judgeToken;

    @Value("${voj-judge-server.remote-judge.open}")
    private Boolean openRemoteJudge;

    @PostMapping(value = "/judge")
    public CommonResult submitProblemJudge(@RequestBody JudgeDTO toJudge) {
        if (!toJudge.getToken().equals(judgeToken)) {
            return CommonResult.errorResponse("对不起！您使用的判题服务调用凭证不正确！访问受限！", ResultStatus.ACCESS_DENIED);
        }

        Judge judge = toJudge.getJudge();

        if (judge == null || judge.getSubmitId() == null || judge.getUid() == null || judge.getPid() == null) {
            return CommonResult.errorResponse("调用参数错误！请检查您的调用参数！");
        }

        judgeService.localJudge(judge);

        return CommonResult.successResponse("判题机评测完成！");
    }

    @PostMapping(value = "/compile-spj")
    public CommonResult compileSpj(@RequestBody CompileDTO compileDTO) {
        if (!compileDTO.getToken().equals(judgeToken)) {
            return CommonResult.errorResponse("对不起！您使用的判题服务调用凭证不正确！访问受限！", ResultStatus.ACCESS_DENIED);
        }

        try {
            judgeService.compileSpj(compileDTO.getCode(), compileDTO.getPid(), compileDTO.getLanguage(),
                    compileDTO.getExtraFiles());
            return CommonResult.successResponse(null, "编译成功！");
        } catch (SystemException systemException) {
            return CommonResult.errorResponse(systemException.getStderr(), ResultStatus.SYSTEM_ERROR);
        }
    }

    @PostMapping(value = "/compile-interactive")
    public CommonResult compileInteractive(@RequestBody CompileDTO compileDTO) {
        if (!compileDTO.getToken().equals(judgeToken)) {
            return CommonResult.errorResponse("对不起！您使用的判题服务调用凭证不正确！访问受限！", ResultStatus.ACCESS_DENIED);
        }

        try {
            judgeService.compileInteractive(compileDTO.getCode(), compileDTO.getPid(), compileDTO.getLanguage(),
                    compileDTO.getExtraFiles());
            return CommonResult.successResponse(null, "编译成功！");
        } catch (SystemException systemException) {
            return CommonResult.errorResponse(systemException.getStderr(), ResultStatus.SYSTEM_ERROR);
        }
    }

    @PostMapping(value = "/remote-judge")
    public CommonResult remoteJudge(@RequestBody JudgeDTO toJudge) {
        if (!openRemoteJudge) {
            return CommonResult.errorResponse("对不起！该判题服务器未开启远程虚拟判题功能！", ResultStatus.ACCESS_DENIED);
        }

        if (!toJudge.getToken().equals(judgeToken)) {
            return CommonResult.errorResponse("对不起！您使用的判题服务调用凭证不正确！访问受限！", ResultStatus.ACCESS_DENIED);
        }

        if (toJudge.getJudge() == null) {
            return CommonResult.errorResponse("请求参数不能为空！");
        }

        judgeService.remoteJudge(toJudge);

        return CommonResult.successResponse("提交成功");
    }

}