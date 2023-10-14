package com.simplefanc.voj.judger.judge.local;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileReader;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.simplefanc.voj.common.constants.JudgeMode;
import com.simplefanc.voj.common.pojo.entity.problem.Problem;
import com.simplefanc.voj.common.pojo.entity.problem.ProblemCase;
import com.simplefanc.voj.judger.common.constants.JudgeDir;
import com.simplefanc.voj.judger.common.exception.SystemException;
import com.simplefanc.voj.judger.dao.ProblemCaseEntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * @Author: chenfan
 * @Date: 2021/4/16 13:21
 * @Description: 判题流程解耦重构2.0，该类只负责题目测试数据的检查与初始化
 */
@Component
@RequiredArgsConstructor
public class ProblemTestCaseUtils {

    private final ProblemCaseEntityService problemCaseEntityService;

    public JSONObject loadTestCaseInfo(Problem problem) throws SystemException {
        Long problemId = problem.getId();
        String testCasesDir = JudgeDir.TEST_CASE_DIR + File.separator + "problem_" + problem.getId();
        String version = problem.getCaseVersion();
        String mode = problem.getJudgeMode();

        if (FileUtil.exist(testCasesDir + File.separator + "info")) {
            FileReader fileReader = new FileReader(testCasesDir + File.separator + "info", CharsetUtil.UTF_8);
            String infoStr = fileReader.readString();
            JSONObject testcaseInfo = JSONUtil.parseObj(infoStr);
            // 测试样例被改动需要重新生成
            if (!version.equals(testcaseInfo.getStr("version", null))) {
                return tryInitTestCaseInfo(testCasesDir, problemId, version, mode);
            }
            return testcaseInfo;
        } else {
            return tryInitTestCaseInfo(testCasesDir, problemId, version, mode);
        }
    }

    /**
     * 若没有测试数据，则尝试从数据库获取并且初始化到本地，如果数据库中该题目测试数据为空，rsync同步也出了问题，则直接判系统错误
     *
     * @param testCasesDir
     * @param problemId
     * @param version
     * @param mode
     * @return
     * @throws SystemException
     */
    private JSONObject tryInitTestCaseInfo(String testCasesDir, Long problemId, String version, String mode)
            throws SystemException {

        QueryWrapper<ProblemCase> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("pid", problemId);
        List<ProblemCase> problemCases = problemCaseEntityService.list(queryWrapper);
        // 数据库也为空的话
        if (problemCases.isEmpty()) {
            throw new SystemException("problemID:[" + problemId + "] test case has not found.", null, null);
        }

        // 文件上传
        if(StrUtil.isEmpty(problemCases.get(0).getOutput())
                || (problemCases.get(0).getOutput().endsWith(".out") || problemCases.get(0).getOutput().endsWith(".ans"))) {
            // 如果本地对应文件夹也为空，说明文件丢失了
            if (FileUtil.isEmpty(new File(testCasesDir))) {
                throw new SystemException("problemID:[" + problemId + "] test case has not found.", null, null);
            } else {
                return initLocalTestCase(mode, version, testCasesDir, problemCases);
            }
        } else { // 手动输入
            List<HashMap<String, Object>> testCases = new LinkedList<>();
            for (ProblemCase problemCase : problemCases) {
                HashMap<String, Object> tmp = new HashMap<>();
                tmp.put("input", problemCase.getInput());
                tmp.put("output", problemCase.getOutput());
                tmp.put("caseId", problemCase.getId());
                tmp.put("score", problemCase.getScore());
                testCases.add(tmp);
            }
            return initTestCase(testCases, problemId, version, mode);
        }
    }

    /**
     * 本地有文件，进行数据初始化 生成json文件
     *
     * @param mode
     * @param version
     * @param testCasesDir
     * @param problemCaseList
     * @return
     */
    private JSONObject initLocalTestCase(String mode, String version, String testCasesDir,
                                        List<ProblemCase> problemCaseList) {
        JSONObject result = new JSONObject();
        result.set("mode", mode);
        result.set("version", version);
        result.set("testCasesSize", problemCaseList.size());
        result.set("testCases", new JSONArray());

        for (ProblemCase problemCase : problemCaseList) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.set("caseId", problemCase.getId());
            jsonObject.set("score", problemCase.getScore());
            jsonObject.set("inputName", problemCase.getInput());
            jsonObject.set("outputName", problemCase.getOutput());
            // 读取输出文件
            FileReader readFile = new FileReader(testCasesDir + File.separator + problemCase.getOutput(),
                    CharsetUtil.UTF_8);
            String output = readFile.readString().replaceAll("\r\n", "\n");

            // spj或interactive是根据特判程序输出判断结果，所以无需初始化测试数据
            if (JudgeMode.DEFAULT.getMode().equals(mode)) {
                initOutputData(jsonObject, output);
            }

            ((JSONArray) result.get("testCases")).put(jsonObject);
        }

        FileWriter infoFile = new FileWriter(testCasesDir + File.separator + "info", CharsetUtil.UTF_8);
        // 写入记录文件
        infoFile.write(JSONUtil.toJsonStr(result));

        return result;
    }

    /**
     * 本地无文件初始化测试数据，写成json文件
     *
     * @param testCases
     * @param problemId
     * @param version
     * @param mode
     * @return
     * @throws SystemException
     */
    private JSONObject initTestCase(List<HashMap<String, Object>> testCases, Long problemId, String version, String mode)
            throws SystemException {
        // TODO 参数
        if (testCases == null || testCases.isEmpty()) {
            throw new SystemException("题号为：" + problemId + "的评测数据为空！", null, "The test cases does not exist.");
        }

        JSONObject result = new JSONObject();
        result.set("mode", mode);
        result.set("version", version);
        result.set("testCasesSize", testCases.size());

        JSONArray testCaseList = new JSONArray(testCases.size());

        String testCasesDir = JudgeDir.TEST_CASE_DIR + "/problem_" + problemId;

        // 无论有没有测试数据，一旦执行该函数，一律清空，重新生成该题目对应的测试数据文件
        FileUtil.del(testCasesDir);
        for (int index = 0; index < testCases.size(); index++) {
            JSONObject jsonObject = new JSONObject();
            String inputName = (index + 1) + ".in";
            jsonObject.set("caseId", testCases.get(index).get("caseId"));
            jsonObject.set("score", testCases.get(index).getOrDefault("score", null));
            jsonObject.set("inputName", inputName);
            // 生成对应文件
            FileWriter infileWriter = new FileWriter(testCasesDir + "/" + inputName, CharsetUtil.UTF_8);
            // 将该测试数据的输入写入到文件
            infileWriter.write((String) testCases.get(index).get("input"));

            String outputName = (index + 1) + ".out";
            jsonObject.set("outputName", outputName);
            // 生成对应文件
            String outputData = (String) testCases.get(index).get("output");
            FileWriter outFile = new FileWriter(testCasesDir + "/" + outputName, CharsetUtil.UTF_8);
            outFile.write(outputData);

            // spj或interactive是根据特判程序输出判断结果，所以无需初始化测试数据
            if (JudgeMode.DEFAULT.getMode().equals(mode)) {
                initOutputData(jsonObject, outputData);
            }

            testCaseList.add(jsonObject);
        }

        result.set("testCases", testCaseList);

        FileWriter infoFile = new FileWriter(testCasesDir + File.separator + "info", CharsetUtil.UTF_8);
        // 写入记录文件
        infoFile.write(JSONUtil.toJsonStr(result));
        return result;
    }

    private void initOutputData(JSONObject jsonObject, String outputData) {
        // 原数据MD5
        jsonObject.set("outputMd5", DigestUtils.md5DigestAsHex(outputData.getBytes()));
        // 原数据大小
        jsonObject.set("outputSize", outputData.getBytes(StandardCharsets.UTF_8).length);
        // 去掉全部空格的MD5，用来判断pe
        jsonObject.set("allStrippedOutputMd5",
                DigestUtils.md5DigestAsHex(outputData.replaceAll("\\s+", "").getBytes()));
        // 默认去掉文末空格的MD5
        jsonObject.set("EOFStrippedOutputMd5", DigestUtils.md5DigestAsHex(rtrim(outputData).getBytes()));
    }

    /**
     * 去除每行末尾的空白符
     *
     * @param value
     * @return
     */
    private static String rtrim(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[^\\S\\r\\n]+(?=\\n|\\r)|\\s+(?=$)", "");
    }
}