package com.simplefanc.voj.judger.judge.local.strategy;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.simplefanc.voj.common.constants.JudgeStatus;
import com.simplefanc.voj.judger.common.constants.JudgeDir;
import com.simplefanc.voj.judger.common.constants.RunConfig;
import com.simplefanc.voj.judger.common.exception.SystemException;
import com.simplefanc.voj.judger.judge.local.SandboxRun;
import com.simplefanc.voj.judger.judge.local.pojo.JudgeCaseDTO;
import com.simplefanc.voj.judger.judge.local.pojo.JudgeGlobalDTO;
import com.simplefanc.voj.judger.judge.local.pojo.CaseResult;
import com.simplefanc.voj.judger.judge.local.pojo.SandBoxRes;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * @Author: chenfan
 * @Date: 2022/1/2 22:23
 * @Description: 特殊判题 支持testlib
 */

@Component
public class SpecialJudge extends AbstractJudge {

    @Override
    public JSONArray judgeCase(JudgeCaseDTO judgeDTO, JudgeGlobalDTO judgeGlobalDTO) throws SystemException {
        RunConfig runConfig = judgeGlobalDTO.getRunConfig();
        // 调用安全沙箱使用测试点对程序进行测试
        return SandboxRun.testCase(parseRunCommand(runConfig, null, null, null),
                runConfig.getEnvs(), judgeDTO.getTestCaseInputPath(), judgeGlobalDTO.getTestTime(),
                judgeGlobalDTO.getMaxMemory(), judgeDTO.getMaxOutputSize(), judgeGlobalDTO.getMaxStack(),
                runConfig.getExeName(), judgeGlobalDTO.getUserFileId(), judgeGlobalDTO.getUserFileSrc());
    }

    @Override
    public CaseResult processResult(SandBoxRes sandBoxRes, JudgeCaseDTO judgeDTO, JudgeGlobalDTO judgeGlobalDTO)
            throws SystemException {
        CaseResult result = new CaseResult();
        // 如果测试跑题无异常
        if (sandBoxRes.getStatus().equals(JudgeStatus.STATUS_ACCEPTED.getStatus())) {
            success(result, sandBoxRes, judgeDTO, judgeGlobalDTO);
        } else if (sandBoxRes.getStatus().equals(JudgeStatus.STATUS_TIME_LIMIT_EXCEEDED.getStatus())) {
            result.setStatus(JudgeStatus.STATUS_TIME_LIMIT_EXCEEDED.getStatus());
        } else if (sandBoxRes.getExitCode() != 0) {
            abort(result, sandBoxRes);
        } else {
            result.setStatus(sandBoxRes.getStatus());
        }

        // b
        result.setMemory(sandBoxRes.getMemory());
        // ns->ms
        result.setTime(sandBoxRes.getTime());

        return result;
    }

    private void abort(CaseResult result, SandBoxRes sandBoxRes) {
        result.setStatus(JudgeStatus.STATUS_RUNTIME_ERROR.getStatus());
        if (sandBoxRes.getExitCode() < 32) {
            result.setErrMsg(String.format("The program return exit status code: %s (%s)\n", sandBoxRes.getExitCode(),
                    SandboxRun.SIGNALS.get(sandBoxRes.getExitCode())));
        } else {
            result.setErrMsg(String.format("The program return exit status code: %s\n", sandBoxRes.getExitCode()));
        }
    }

    private void success(CaseResult result, SandBoxRes sandBoxRes, JudgeCaseDTO judgeDTO, JudgeGlobalDTO judgeGlobalDTO) throws SystemException {
        // 对结果的时间损耗和空间损耗与题目限制做比较，判断是否mle和tle
        if (sandBoxRes.getTime() > judgeGlobalDTO.getMaxTime()) {
            result.setStatus(JudgeStatus.STATUS_TIME_LIMIT_EXCEEDED.getStatus());
        } else if (sandBoxRes.getMemory() > judgeGlobalDTO.getMaxMemory() * 1024) {
            result.setStatus(JudgeStatus.STATUS_MEMORY_LIMIT_EXCEEDED.getStatus());
        } else {
            // 对于当前测试样例，用户程序的输出对应生成的文件
            String userOutputFilePath = judgeGlobalDTO.getRunDir() + File.separator + judgeDTO.getTestCaseNum() + ".out";
            FileWriter stdWriter = new FileWriter(userOutputFilePath);
            stdWriter.write(sandBoxRes.getStdout());

            RunConfig spjRunConfig = judgeGlobalDTO.getSpjRunConfig();

            // 特判程序的路径
            String spjExeSrc = JudgeDir.SPJ_WORKPLACE_DIR + File.separator + judgeGlobalDTO.getProblemId()
                    + File.separator + spjRunConfig.getExeName();

            String userOutputFileName = judgeGlobalDTO.getProblemId() + "_user_output";
            String testCaseInputFileName = judgeGlobalDTO.getProblemId() + "_input";
            String testCaseOutputFileName = judgeGlobalDTO.getProblemId() + "_output";
            // 进行spj程序运行比对
            JSONObject spjResult = spjRunAndCheckResult(userOutputFilePath, userOutputFileName,
                    judgeDTO.getTestCaseInputPath(), testCaseInputFileName, judgeDTO.getTestCaseOutputPath(),
                    testCaseOutputFileName, spjExeSrc, spjRunConfig);

            // 删除用户输出文件
            FileUtil.del(userOutputFilePath);

            int code = spjResult.getInt("code");
            if (code == SPJ_WA) {
                result.setStatus(JudgeStatus.STATUS_WRONG_ANSWER.getStatus());
            } else if (code == SPJ_AC) {
                result.setStatus(JudgeStatus.STATUS_ACCEPTED.getStatus());
            } else if (code == SPJ_PE) {
                result.setStatus(JudgeStatus.STATUS_PRESENTATION_ERROR.getStatus());
            } else if (code == SPJ_PC) {
                result.setStatus(JudgeStatus.STATUS_PARTIAL_ACCEPTED.getStatus());
                result.setPercentage(spjResult.getDouble("percentage"));
            } else {
                result.setStatus(JudgeStatus.STATUS_SYSTEM_ERROR.getStatus());
            }

            String spjErrMsg = spjResult.getStr("errMsg");
            if (StrUtil.isNotEmpty(spjErrMsg)) {
                result.setErrMsg(spjErrMsg.substring(0, Math.min(1024 * 1024, spjErrMsg.length())));
            }
        }
    }

    // TODO 参数过多
    private JSONObject spjRunAndCheckResult(String userOutputFilePath, String userOutputFileName,
                                            String testCaseInputFilePath, String testCaseInputFileName, String testCaseOutputFilePath,
                                            String testCaseOutputFileName, String spjExeSrc, RunConfig spjRunConfig) throws SystemException {

        // 调用安全沙箱运行spj程序
        JSONArray spjJudgeResultList = SandboxRun.spjCheckResult(
                parseRunCommand(spjRunConfig, testCaseInputFileName, userOutputFileName,
                        testCaseOutputFileName),
                spjRunConfig.getEnvs(), userOutputFilePath, userOutputFileName, testCaseInputFilePath,
                testCaseInputFileName, testCaseOutputFilePath, testCaseOutputFileName, spjExeSrc,
                spjRunConfig.getExeName());

        JSONObject result = new JSONObject();

        JSONObject spjJudgeResult = (JSONObject) spjJudgeResultList.get(0);

        // 获取跑题用户输出或错误输出
        String spjErrOut = ((JSONObject) spjJudgeResult.get("files")).getStr("stderr");
        String spjStdOut = ((JSONObject) spjJudgeResult.get("files")).getStr("stdout");
        if (StrUtil.isNotEmpty(spjErrOut)) {
            result.set("errMsg", spjErrOut);
        }

        // 退出状态码
        int exitCode = spjJudgeResult.getInt("exitStatus");
        // 如果测试跑题无异常
        if (spjJudgeResult.getInt("status").intValue() == JudgeStatus.STATUS_ACCEPTED.getStatus()) {
            if (exitCode == JudgeStatus.STATUS_ACCEPTED.getStatus()) {
                result.set("code", SPJ_AC);
            } else {
                result.set("code", exitCode);
            }
        } else if (spjJudgeResult.getInt("status").intValue() == JudgeStatus.STATUS_RUNTIME_ERROR.getStatus()) {
            if (exitCode == SPJ_WA || exitCode == SPJ_ERROR || exitCode == SPJ_AC || exitCode == SPJ_PE) {
                result.set("code", exitCode);
            } else if (exitCode == SPJ_PC) {
                result.set("code", exitCode);
                if (NumberUtil.isNumber(spjStdOut)) {
                    double percentage = 0.0;
                    percentage = Double.parseDouble(spjStdOut) / 100;
                    if (percentage == 1) {
                        result.set("code", SPJ_AC);
                    } else {
                        result.set("percentage", percentage);
                    }
                }
            } else {
                if (StrUtil.isNotEmpty(spjErrOut)) {
                    // 适配testlib.h 根据错误信息前缀判断
                    return parseTestLibErr(spjErrOut);
                } else {
                    result.set("code", SPJ_ERROR);
                }
            }
        } else {
            result.set("code", SPJ_ERROR);
        }

        return result;
    }

}