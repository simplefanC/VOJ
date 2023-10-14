package com.simplefanc.voj.judger.judge.local;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.simplefanc.voj.common.constants.JudgeCaseMode;
import com.simplefanc.voj.common.constants.JudgeMode;
import com.simplefanc.voj.common.constants.JudgeStatus;
import com.simplefanc.voj.common.pojo.entity.judge.Judge;
import com.simplefanc.voj.common.pojo.entity.problem.Problem;
import com.simplefanc.voj.judger.common.constants.JudgeDir;
import com.simplefanc.voj.judger.common.constants.RunConfig;
import com.simplefanc.voj.judger.common.exception.SystemException;
import com.simplefanc.voj.judger.common.utils.JudgeUtil;
import com.simplefanc.voj.judger.common.utils.ThreadPoolUtil;
import com.simplefanc.voj.judger.judge.local.pojo.JudgeCaseDTO;
import com.simplefanc.voj.judger.judge.local.pojo.JudgeGlobalDTO;
import com.simplefanc.voj.judger.judge.local.pojo.CaseResult;
import com.simplefanc.voj.judger.judge.local.strategy.AbstractJudge;
import com.simplefanc.voj.judger.judge.local.strategy.DefaultJudge;
import com.simplefanc.voj.judger.judge.local.strategy.InteractiveJudge;
import com.simplefanc.voj.judger.judge.local.strategy.SpecialJudge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

/**
 * @Author: chenfan
 * @Date: 2021/4/16 12:15
 * @Description: 运行判题任务
 */
@Component
@RequiredArgsConstructor
public class JudgeRun {

    private final DefaultJudge defaultJudge;

    private final SpecialJudge specialJudge;

    private final InteractiveJudge interactiveJudge;

    private final ProblemTestCaseUtils problemTestCaseUtils;

    public List<CaseResult> judgeAllCase(Judge judge, Problem problem, String userFileId, String userFileSrc, Boolean getUserOutput)
            throws SystemException, ExecutionException, InterruptedException, UnsupportedEncodingException {

        JudgeGlobalDTO judgeGlobalDTO = getJudgeGlobalDTO(judge, problem, userFileId, userFileSrc, getUserOutput);

        List<JudgeTask> judgeTasks = getJudgeTasks(judgeGlobalDTO);
        if (JudgeCaseMode.ITERATE_UNTIL_WRONG.getMode().equals(problem.getJudgeCaseMode())) {
            // 顺序评测测试点，遇到非AC就停止！
            return iterateJudgeAllCase(judgeTasks);
        } else {
            return defaultJudgeAllCase(judgeTasks);
        }
    }

    private List<CaseResult> iterateJudgeAllCase(List<JudgeTask> judgeTasks) throws ExecutionException, InterruptedException {
        List<CaseResult> result = new LinkedList<>();
        for (JudgeTask judgeTask : judgeTasks) {
            // 提交到线程池进行执行
            FutureTask<CaseResult> futureTask = new FutureTask<>(judgeTask);
            ThreadPoolUtil.getInstance().getThreadPool().submit(futureTask);
            final CaseResult judgeRes = futureTask.get();
            result.add(judgeRes);
            Integer status = judgeRes.getStatus();
            if (!JudgeStatus.STATUS_ACCEPTED.getStatus().equals(status)) {
                break;
            }
        }
        return result;
    }

    private List<CaseResult> defaultJudgeAllCase(List<JudgeTask> judgeTasks) throws InterruptedException, ExecutionException {
        ExecutorService threadPool = ThreadPoolUtil.getInstance().getThreadPool();
        CompletableFuture[] futures = new CompletableFuture[judgeTasks.size()];
        for (int i = 0; i < judgeTasks.size(); i++) {
            final JudgeTask judgeTask = judgeTasks.get(i);
            futures[i]  = CompletableFuture.supplyAsync(() -> {
                try {
                    // 普通方法
                    return judgeTask.call();
                } catch (SystemException e) {
                    throw new RuntimeException(e);
                }
            }, threadPool);
        }
        // allOf() 方法会等到所有的 CompletableFuture 都运行完成之后再返回
        CompletableFuture<Void> headerFuture = CompletableFuture.allOf(futures);
        // 都运行完了之后再继续执行
        headerFuture.join();
        List<CaseResult> res = new ArrayList<>();
        for (int i = 0; i < judgeTasks.size(); i++) {
            res.add((CaseResult) futures[i].get());
        }
        return res;
    }

    private List<JudgeTask> getJudgeTasks(JudgeGlobalDTO judgeGlobalDTO) {
        List<JudgeTask> judgeTasks = new ArrayList<>();
        final JSONArray testcaseList = (JSONArray) judgeGlobalDTO.getTestCaseInfo().get("testCases");
        for (int index = 0; index < testcaseList.size(); index++) {
            JSONObject testcase = (JSONObject) testcaseList.get(index);
            final int testCaseNum = index + 1;
            // 输入文件名
            final String inputFileName = testcase.getStr("inputName");
            // 输出文件名
            final String outputFileName = testcase.getStr("outputName");
            // 题目数据的输入文件的路径
            final String testCaseInputPath = judgeGlobalDTO.getTestCasesDir() + File.separator + inputFileName;
            // 题目数据的输出文件的路径
            final String testCaseOutputPath = judgeGlobalDTO.getTestCasesDir() + File.separator + outputFileName;
            // 数据库表的测试样例id
            final Long caseId = testcase.getLong("caseId", null);
            // 该测试点的满分
            final Integer score = testcase.getInt("score", 0);

            final Long maxOutputSize = Math.max(testcase.getLong("outputSize", 0L) * 2, 16 * 1024 * 1024L);

            JudgeCaseDTO judgeDTO = JudgeCaseDTO.builder()
                    .testCaseNum(testCaseNum)
                    .testCaseInputFileName(inputFileName)
                    .testCaseInputPath(testCaseInputPath)
                    .testCaseOutputFileName(outputFileName)
                    .testCaseOutputPath(testCaseOutputPath)
                    .problemCaseId(caseId)
                    .score(score)
                    .maxOutputSize(maxOutputSize)
                    .build();
            judgeTasks.add(new JudgeTask(judgeDTO, judgeGlobalDTO));
        }
        return judgeTasks;
    }

    private JudgeGlobalDTO getJudgeGlobalDTO(Judge judge, Problem problem, String userFileId, String userFileSrc, Boolean getUserOutput) throws SystemException, UnsupportedEncodingException {
        Long submitId = judge.getSubmitId();
        String judgeLanguage = judge.getLanguage();

        // 默认给题目限制时间+200ms用来测评
        Long testTime = (long) problem.getTimeLimit() + 200;

        JudgeMode judgeMode = JudgeMode.getJudgeMode(problem.getJudgeMode());
        if (judgeMode == null) {
            throw new RuntimeException(
                    "The judge mode of problem " + problem.getProblemId() + " error:" + problem.getJudgeMode());
        }

        // 从文件中加载测试数据json
        JSONObject testCasesInfo = problemTestCaseUtils.loadTestCaseInfo(problem);
        if (testCasesInfo == null) {
            throw new SystemException("The evaluation data of the problem does not exist", null, null);
        }

        // 测试数据文件所在文件夹
        String testCasesDir = JudgeDir.TEST_CASE_DIR + File.separator + "problem_" + problem.getId();

        // 用户输出的文件夹
        String runDir = JudgeDir.RUN_WORKPLACE_DIR + File.separator + submitId;

        RunConfig runConfig = RunConfig.getRunnerByLanguage(judgeLanguage);
        RunConfig spjConfig = RunConfig.getRunnerByLanguage("SPJ-" + problem.getSpjLanguage());
        RunConfig interactiveConfig = RunConfig.getRunnerByLanguage("INTERACTIVE-" + problem.getSpjLanguage());

        return JudgeGlobalDTO.builder()
                .problemId(problem.getId())
                .judgeMode(judgeMode)
                .userFileId(userFileId)
                .userFileSrc(userFileSrc)
                .runDir(runDir)
                .testTime(testTime)
                .maxMemory((long) problem.getMemoryLimit())
                .maxTime((long) problem.getTimeLimit())
                .maxStack(problem.getStackLimit())
                .testCasesDir(testCasesDir)
                .testCaseInfo(testCasesInfo)
                .judgeExtraFiles(JudgeUtil.getProblemExtraFileMap(problem, "judge"))
                .runConfig(runConfig)
                .spjRunConfig(spjConfig)
                .interactiveRunConfig(interactiveConfig)
                .needUserOutputFile(getUserOutput)
                .removeEOLBlank(problem.getIsRemoveEndBlank()).build();
    }

    class JudgeTask implements Callable<CaseResult> {
        JudgeCaseDTO judgeDTO;
        JudgeGlobalDTO judgeGlobalDTO;

        public JudgeTask(JudgeCaseDTO judgeDTO, JudgeGlobalDTO judgeGlobalDTO) {
            this.judgeDTO = judgeDTO;
            this.judgeGlobalDTO = judgeGlobalDTO;
        }

        @Override
        public CaseResult call() throws SystemException {
            final AbstractJudge abstractJudge = getAbstractJudge(judgeGlobalDTO.getJudgeMode());

            CaseResult result = abstractJudge.judge(judgeDTO, judgeGlobalDTO);
            result.setCaseId(judgeDTO.getProblemCaseId());
            result.setScore(judgeDTO.getScore());
            result.setInputFileName(judgeDTO.getTestCaseInputFileName());
            result.setOutputFileName(judgeDTO.getTestCaseOutputFileName());
            return result;
        }

        private AbstractJudge getAbstractJudge(JudgeMode judgeMode) {
            switch (judgeMode) {
                case DEFAULT:
                    return defaultJudge;
                case SPJ:
                    return specialJudge;
                case INTERACTIVE:
                    return interactiveJudge;
                default:
                    throw new RuntimeException("The problem judge mode is error:" + judgeMode);
            }
        }

    }

}