package com.simplefanc.voj.judger.judge.local;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.simplefanc.voj.common.constants.JudgeStatus;
import com.simplefanc.voj.judger.common.exception.SystemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: chenfan
 * @Date: 2021/1/23 13:44
 * @Description: 调用判题安全沙箱
 */

/**
 * args: string[]; // command line argument
 * env?: string[]; // environment
 * <p>
 * // specifies file input / pipe collector for program file descriptors
 * files?: (LocalFile | MemoryFile | PreparedFile | Pipe | null)[];
 * tty?: boolean; // enables tty on the input and output pipes (should have just one input & one output)
 * // Notice: must have TERM environment variables (e.g. TERM=xterm)
 * <p>
 * // limitations
 * cpuLimit?: number;     // ns
 * realCpuLimit?: number; // deprecated: use clock limit instead (still working)
 * clockLimit?: number;   // ns
 * memoryLimit?: number;  // byte
 * stackLimit?: number;   // byte (N/A on windows, macOS cannot set over 32M)
 * procLimit?: number;
 * <p>
 * // copy the correspond file to the container dst path
 * copyIn?: {[dst:string]:LocalFile | MemoryFile | PreparedFile};
 * <p>
 * // copy out specifies files need to be copied out from the container after execution
 * copyOut?: string[];
 * // similar to copyOut but stores file in executor service and returns fileId, later download through /file/:fileId
 * copyOutCached?: string[];
 * // specifies the directory to dump container /w content
 * copyOutDir: string
 * // specifies the max file size to copy out
 * copyOutMax: number; // byte
 */
@Slf4j(topic = "voj")
public class SandboxRun {
    /**
     * 单例模式
     */
    private static final SandboxRun INSTANCE = new SandboxRun();

    private SandboxRun() {
    }

    private static final RestTemplate REST_TEMPLATE;

    static {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(20000);
        requestFactory.setReadTimeout(180000);
        REST_TEMPLATE = new RestTemplate(requestFactory);
    }

    public static RestTemplate getRestTemplate() {
        return REST_TEMPLATE;
    }

    private static final String SANDBOX_BASE_URL = "http://localhost:5050";

    public static String getSandboxBaseUrl() {
        return SANDBOX_BASE_URL;
    }

    private static final int MAX_PROCESS_NUMBER = 128;

    private static final int TIME_LIMIT_MS = 16000;

    private static final int MEMORY_LIMIT_MB = 512;

    private static final int STACK_LIMIT_MB = 128;

    private static final int STDIO_SIZE_MB = 32;

    /**
     * "files": [{ "content": "" }, { "name": "stdout", "max": 1024 * 1024 * 32 }, {
     * "name": "stderr", "max": 1024 * 1024 * 32 }]
     */
    private static final JSONArray COMPILE_FILES = new JSONArray();

    static {
        JSONObject content = new JSONObject();
        content.set("content", "");

        JSONObject stdout = new JSONObject();
        stdout.set("name", "stdout");
        stdout.set("max", 1024 * 1024 * STDIO_SIZE_MB);

        JSONObject stderr = new JSONObject();
        stderr.set("name", "stderr");
        stderr.set("max", 1024 * 1024 * STDIO_SIZE_MB);
        COMPILE_FILES.put(content);
        COMPILE_FILES.put(stdout);
        COMPILE_FILES.put(stderr);
    }

    public static final HashMap<String, Integer> RESULT_STATUS_MAP = new HashMap<>() {
        {
            put("Time Limit Exceeded", JudgeStatus.STATUS_TIME_LIMIT_EXCEEDED.getStatus());
            put("Memory Limit Exceeded", JudgeStatus.STATUS_MEMORY_LIMIT_EXCEEDED.getStatus());
            put("Output Limit Exceeded", JudgeStatus.STATUS_RUNTIME_ERROR.getStatus());
            put("Accepted", JudgeStatus.STATUS_ACCEPTED.getStatus());
            put("Nonzero Exit Status", JudgeStatus.STATUS_RUNTIME_ERROR.getStatus());
            put("Internal Error", JudgeStatus.STATUS_SYSTEM_ERROR.getStatus());
            put("File Error", JudgeStatus.STATUS_SYSTEM_ERROR.getStatus());
            put("Signalled", JudgeStatus.STATUS_RUNTIME_ERROR.getStatus());
        }
    };

    public static final Map<Integer, String> SIGNALS = new HashMap<>() {
        {
            put(0, "");
            put(1, "Hangup");
            put(2, "Interrupt");
            put(3, "Quit");
            put(4, "Illegal instruction");
            put(5, "Trace/breakpoint trap");
            put(6, "Aborted");
            put(7, "Bus error");
            put(8, "Floating point exception");
            put(9, "Killed");
            put(10, "User defined signal 1");
            put(11, "Segmentation fault");
            put(12, "User defined signal 2");
            put(13, "Broken pipe");
            put(14, "Alarm clock");
            put(15, "Terminated");
            put(16, "Stack fault");
            put(17, "Child exited");
            put(18, "Continued");
            put(19, "Stopped (signal)");
            put(20, "Stopped");
            put(21, "Stopped (tty input)");
            put(22, "Stopped (tty output)");
            put(23, "Urgent I/O condition");
            put(24, "CPU time limit exceeded");
            put(25, "File size limit exceeded");
            put(26, "Virtual timer expired");
            put(27, "Profiling timer expired");
            put(28, "Window changed");
            put(29, "I/O possible");
            put(30, "Power failure");
            put(31, "Bad system call");
        }
    };

    public JSONArray run(String uri, JSONObject param) throws SystemException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(JSONUtil.toJsonStr(param), headers);
        ResponseEntity<String> postForEntity;
        try {
            postForEntity = REST_TEMPLATE.postForEntity(SANDBOX_BASE_URL + uri, request, String.class);
            // TODO 疑似出现OOM 普通评测：如果沙盒运行程序不是 Accepted 可以不获取 stdout
            // 临时解决：顺序评测 and 降低程序的并发数
            // 如果测试数据标准输出真的特别大 只有增大JVM配置
            return JSONUtil.parseArray(postForEntity.getBody());
        } catch (RestClientResponseException ex) {
            if (ex.getRawStatusCode() != 200) {
                throw new SystemException("Cannot connect to sandbox service.", null, ex.getResponseBodyAsString());
            }
        } catch (Exception e) {
            throw new SystemException("Call SandBox Error.", null, e.getMessage());
        }
        return null;
    }

    public static void delFile(String fileId) {
        try {
            REST_TEMPLATE.delete(SANDBOX_BASE_URL + "/file/{0}", fileId);
        } catch (RestClientResponseException ex) {
            if (ex.getRawStatusCode() != 200) {
                log.error("安全沙箱判题的删除内存中的文件缓存操作异常----------------->{}", ex.getResponseBodyAsString());
            }
        }
    }

    /**
     * @param maxCpuTime        最大编译的cpu时间 ms
     * @param maxRealTime       最大编译的真实时间 ms
     * @param maxMemory         最大编译的空间 b
     * @param maxStack          最大编译的栈空间 b
     * @param srcName           编译的源文件名字
     * @param exeName           编译生成的exe文件名字
     * @param args              编译的cmd参数
     * @param envs              编译的环境变量
     * @param code              编译的源代码
     * @param extraFiles        编译所需的额外文件 key:文件名，value:文件内容
     * @param needCopyOutCached 是否需要生成编译后的用户程序exe文件
     * @param needCopyOutExe    是否需要生成用户程序的缓存文件，即生成用户程序id
     * @param copyOutDir        生成编译后的用户程序exe文件的指定路径
     * @MethodName compile
     * @Description 编译运行
     * @Return
     * @Since 2022/1/3
     */
    public static JSONArray compile(Long maxCpuTime, Long maxRealTime, Long maxMemory, Long maxStack, String srcName,
                                    String exeName, List<String> args, List<String> envs, String code, HashMap<String, String> extraFiles,
                                    Boolean needCopyOutCached, Boolean needCopyOutExe, String copyOutDir) throws SystemException {
        JSONObject cmd = new JSONObject();
        cmd.set("args", args);
        cmd.set("env", envs);
        cmd.set("files", COMPILE_FILES);
        // ms-->ns
        cmd.set("cpuLimit", maxCpuTime * 1000 * 1000L);
        cmd.set("clockLimit", maxRealTime * 1000 * 1000L);
        // byte
        cmd.set("memoryLimit", maxMemory);
        cmd.set("procLimit", MAX_PROCESS_NUMBER);
        cmd.set("stackLimit", maxStack);

        JSONObject fileContent = new JSONObject();
        fileContent.set("content", code);

        JSONObject copyIn = new JSONObject();
        copyIn.set(srcName, fileContent);

        if (extraFiles != null) {
            for (Map.Entry<String, String> entry : extraFiles.entrySet()) {
                if (StrUtil.isNotEmpty(entry.getKey()) && StrUtil.isNotEmpty(entry.getValue())) {
                    JSONObject content = new JSONObject();
                    content.set("content", entry.getValue());
                    copyIn.set(entry.getKey(), content);
                }
            }
        }

        cmd.set("copyIn", copyIn);
        cmd.set("copyOut", new JSONArray().put("stdout").put("stderr"));

        if (needCopyOutCached) {
            cmd.set("copyOutCached", new JSONArray().put(exeName));
        }

        if (needCopyOutExe) {
            cmd.set("copyOutDir", copyOutDir);
        }

        JSONObject param = new JSONObject();
        param.set("cmd", new JSONArray().put(cmd));

        JSONArray result = INSTANCE.run("/run", param);
        JSONObject tmp = (JSONObject) result.get(0);
        ((JSONObject) result.get(0)).set("status", RESULT_STATUS_MAP.get(tmp.getStr("status")));
        return result;
    }

    /**
     * @param args          普通评测运行cmd的命令参数
     * @param envs          普通评测运行的环境变量
     * @param testCasePath  题目数据的输入文件路径
     * @param maxTime       评测的最大限制时间 ms
     * @param maxOutputSize 评测的最大输出大小 kb
     * @param maxStack      评测的最大限制栈空间 mb
     * @param exeName       评测的用户程序名称
     * @param fileId        评测的用户程序文件id
     * @param fileSrc       评测的用户程序文件绝对路径，如果userFileId存在则为null
     * @MethodName testCase
     * @Description 普通评测
     * @Return JSONArray
     * @Since 2022/1/3
     */
    public static JSONArray testCase(List<String> args, List<String> envs, String testCasePath, Long maxTime,
                                     Long maxMemory, Long maxOutputSize, Integer maxStack, String exeName, String fileId, String fileSrc)
            throws SystemException {

        JSONObject cmd = new JSONObject();
        cmd.set("args", args);
        cmd.set("env", envs);

        JSONArray files = new JSONArray();
        JSONObject content = new JSONObject();
        content.set("src", testCasePath);

        JSONObject stdout = new JSONObject();
        stdout.set("name", "stdout");
        stdout.set("max", maxOutputSize);

        JSONObject stderr = new JSONObject();
        stderr.set("name", "stderr");
        stderr.set("max", 1024 * 1024 * 16);
        files.put(content);
        files.put(stdout);
        files.put(stderr);

        cmd.set("files", files);

        // ms-->ns
        cmd.set("cpuLimit", maxTime * 1000 * 1000L);
        cmd.set("clockLimit", maxTime * 1000 * 1000L * 3);
        // byte
        cmd.set("memoryLimit", (maxMemory + 100) * 1024 * 1024L);
        cmd.set("procLimit", MAX_PROCESS_NUMBER);
        cmd.set("stackLimit", maxStack * 1024 * 1024L);

        JSONObject exeFile = new JSONObject();
        if (StrUtil.isNotEmpty(fileId)) {
            exeFile.set("fileId", fileId);
        } else {
            exeFile.set("src", fileSrc);
        }
        JSONObject copyIn = new JSONObject();
        copyIn.set(exeName, exeFile);

        cmd.set("copyIn", copyIn);
        cmd.set("copyOut", new JSONArray().put("stdout").put("stderr"));

        JSONObject param = new JSONObject();
        param.set("cmd", new JSONArray().put(cmd));

        // 调用判题安全沙箱
        JSONArray result = INSTANCE.run("/run", param);

        final JSONObject jsonObject = (JSONObject) result.get(0);
        jsonObject.set("status", RESULT_STATUS_MAP.get(jsonObject.getStr("status")));
        return result;
    }

    /**
     * @param args                   特殊判题的运行cmd命令参数
     * @param envs                   特殊判题的运行环境变量
     * @param userOutputFilePath     用户程序输出文件的路径
     * @param userOutputFileName     用户程序输出文件的名字
     * @param testCaseInputFilePath  题目数据的输入文件的路径
     * @param testCaseInputFileName  题目数据的输入文件的名字
     * @param testCaseOutputFilePath 题目数据的输出文件的路径
     * @param testCaseOutputFileName 题目数据的输出文件的路径
     * @param spjExeSrc              特殊判题的exe文件的路径
     * @param spjExeName             特殊判题的exe文件的名字
     * @MethodName spjCheckResult
     * @Description 特殊判题的评测
     * @Return JSONArray
     * @Since 2022/1/3
     */
    public static JSONArray spjCheckResult(List<String> args, List<String> envs, String userOutputFilePath,
                                           String userOutputFileName, String testCaseInputFilePath, String testCaseInputFileName,
                                           String testCaseOutputFilePath, String testCaseOutputFileName, String spjExeSrc, String spjExeName)
            throws SystemException {

        JSONObject cmd = new JSONObject();
        cmd.set("args", args);
        cmd.set("env", envs);

        JSONArray outFiles = new JSONArray();

        JSONObject content = new JSONObject();
        content.set("content", "");

        JSONObject outStdout = new JSONObject();
        outStdout.set("name", "stdout");
        outStdout.set("max", 1024 * 1024 * 16);

        JSONObject outStderr = new JSONObject();
        outStderr.set("name", "stderr");
        outStderr.set("max", 1024 * 1024 * 16);

        outFiles.put(content);
        outFiles.put(outStdout);
        outFiles.put(outStderr);
        cmd.set("files", outFiles);

        // ms-->ns
        cmd.set("cpuLimit", TIME_LIMIT_MS * 1000 * 1000L);
        cmd.set("clockLimit", TIME_LIMIT_MS * 1000 * 1000L * 3);
        // byte
        cmd.set("memoryLimit", MEMORY_LIMIT_MB * 1024 * 1024L);
        cmd.set("procLimit", MAX_PROCESS_NUMBER);
        cmd.set("stackLimit", STACK_LIMIT_MB * 1024 * 1024L);

        JSONObject spjExeFile = new JSONObject();
        spjExeFile.set("src", spjExeSrc);

        JSONObject useOutputFileSrc = new JSONObject();
        useOutputFileSrc.set("src", userOutputFilePath);

        JSONObject stdInputFileSrc = new JSONObject();
        stdInputFileSrc.set("src", testCaseInputFilePath);

        JSONObject stdOutFileSrc = new JSONObject();
        stdOutFileSrc.set("src", testCaseOutputFilePath);

        JSONObject spjCopyIn = new JSONObject();

        spjCopyIn.set(spjExeName, spjExeFile);
        spjCopyIn.set(userOutputFileName, useOutputFileSrc);
        spjCopyIn.set(testCaseInputFileName, stdInputFileSrc);
        spjCopyIn.set(testCaseOutputFileName, stdOutFileSrc);

        cmd.set("copyIn", spjCopyIn);
        cmd.set("copyOut", new JSONArray().put("stdout").put("stderr"));

        JSONObject param = new JSONObject();

        param.set("cmd", new JSONArray().put(cmd));

        // 调用判题安全沙箱
        JSONArray result = INSTANCE.run("/run", param);

        JSONObject tmp = (JSONObject) result.get(0);
        ((JSONObject) result.get(0)).set("status", RESULT_STATUS_MAP.get(tmp.getStr("status")));
        return result;
    }

    /**
     * @param args                   cmd的命令参数 评测运行的命令
     * @param envs                   测评的环境变量
     * @param userExeName            用户程序的名字
     * @param userFileId             用户程序在编译后返回的id，主要是对应内存中已编译后的文件
     * @param userFileSrc            用户程序文件的绝对路径，如果userFileId存在则为null
     * @param userMaxTime            用户程序的最大测评时间 ms
     * @param userMaxStack           用户程序的最大测评栈空间 mb
     * @param testCaseInputPath      题目数据的输入文件路径
     * @param testCaseInputFileName  题目数据的输入文件名字
     * @param testCaseOutputFilePath 题目数据的输出文件路径
     * @param testCaseOutputFileName 题目数据的输出文件名字
     * @param userOutputFileName     用户程序的输出文件名字
     * @param interactArgs           交互程序运行的cmd命令参数
     * @param interactEnvs           交互程序运行的环境变量
     * @param interactExeSrc         交互程序的exe文件路径
     * @param interactExeName        交互程序的exe文件名字
     * @MethodName interactTestCase
     * @Description 交互评测
     * @Return JSONArray
     * @Since 2022/1/3
     */
    public static JSONArray interactTestCase(List<String> args, List<String> envs, String userExeName,
                                             String userFileId, String userFileSrc, Long userMaxTime, Long userMaxMemory, Integer userMaxStack,
                                             String testCaseInputPath, String testCaseInputFileName, String testCaseOutputFilePath,
                                             String testCaseOutputFileName, String userOutputFileName, List<String> interactArgs,
                                             List<String> interactEnvs, String interactExeSrc, String interactExeName) throws SystemException {

        /**
         * 注意：用户源代码需要先编译，若是通过编译需要先将文件存入内存，再利用管道判题，同时特殊判题程序必须已编译且存在（否则判题失败，系统错误）！
         */

        JSONObject pipeInputCmd = new JSONObject();
        pipeInputCmd.set("args", args);
        pipeInputCmd.set("env", envs);

        JSONArray files = new JSONArray();

        JSONObject stderr = new JSONObject();
        stderr.set("name", "stderr");
        stderr.set("max", 1024 * 1024 * STDIO_SIZE_MB);

        files.put(new JSONObject());
        files.put(new JSONObject());
        files.put(stderr);

        String inTmp = files.toString().replace("{}", "null");
        pipeInputCmd.set("files", JSONUtil.parseArray(inTmp, false));

        // ms-->ns
        pipeInputCmd.set("cpuLimit", userMaxTime * 1000 * 1000L);
        pipeInputCmd.set("clockLimit", userMaxTime * 1000 * 1000L * 3);

        // byte

        pipeInputCmd.set("memoryLimit", (userMaxMemory + 100) * 1024 * 1024L);
        pipeInputCmd.set("procLimit", MAX_PROCESS_NUMBER);
        pipeInputCmd.set("stackLimit", userMaxStack * 1024 * 1024L);

        JSONObject exeFile = new JSONObject();
        if (StrUtil.isNotEmpty(userFileId)) {
            exeFile.set("fileId", userFileId);
        } else {
            exeFile.set("src", userFileSrc);
        }
        JSONObject copyIn = new JSONObject();
        copyIn.set(userExeName, exeFile);

        pipeInputCmd.set("copyIn", copyIn);
        pipeInputCmd.set("copyOut", new JSONArray());

        // 管道输出，用户程序输出数据经过特殊判题程序后，得到的最终输出结果。
        JSONObject pipeOutputCmd = new JSONObject();
        pipeOutputCmd.set("args", interactArgs);
        pipeOutputCmd.set("env", interactEnvs);

        JSONArray outFiles = new JSONArray();

        JSONObject outStderr = new JSONObject();
        outStderr.set("name", "stderr");
        outStderr.set("max", 1024 * 1024 * STDIO_SIZE_MB);
        outFiles.put(new JSONObject());
        outFiles.put(new JSONObject());
        outFiles.put(outStderr);
        String outTmp = outFiles.toString().replace("{}", "null");
        pipeOutputCmd.set("files", JSONUtil.parseArray(outTmp, false));

        // ms-->ns
        pipeOutputCmd.set("cpuLimit", userMaxTime * 1000 * 1000L * 2);
        pipeOutputCmd.set("clockLimit", userMaxTime * 1000 * 1000L * 3 * 2);
        // byte
        pipeOutputCmd.set("memoryLimit", (userMaxMemory + 100) * 1024 * 1024L * 2);
        pipeOutputCmd.set("procLimit", MAX_PROCESS_NUMBER);
        pipeOutputCmd.set("stackLimit", STACK_LIMIT_MB * 1024 * 1024L);

        JSONObject spjExeFile = new JSONObject();
        spjExeFile.set("src", interactExeSrc);

        JSONObject stdInputFileSrc = new JSONObject();
        stdInputFileSrc.set("src", testCaseInputPath);

        JSONObject stdOutFileSrc = new JSONObject();
        stdOutFileSrc.set("src", testCaseOutputFilePath);

        JSONObject interactiveCopyIn = new JSONObject();
        interactiveCopyIn.set(interactExeName, spjExeFile);
        interactiveCopyIn.set(testCaseInputFileName, stdInputFileSrc);
        interactiveCopyIn.set(testCaseOutputFileName, stdOutFileSrc);

        pipeOutputCmd.set("copyIn", interactiveCopyIn);
        pipeOutputCmd.set("copyOut", new JSONArray().put(userOutputFileName));

        JSONArray cmdList = new JSONArray();
        cmdList.put(pipeInputCmd);
        cmdList.put(pipeOutputCmd);

        JSONObject param = new JSONObject();
        // 添加cmd指令
        param.set("cmd", cmdList);

        // 添加管道映射
        JSONArray pipeMapping = new JSONArray();
        // 用户程序
        JSONObject user = new JSONObject();

        JSONObject userIn = new JSONObject();
        userIn.set("index", 0);
        userIn.set("fd", 1);

        JSONObject userOut = new JSONObject();
        userOut.set("index", 1);
        userOut.set("fd", 0);

        user.set("in", userIn);
        user.set("out", userOut);
        user.set("max", STDIO_SIZE_MB * 1024 * 1024);
        user.set("proxy", true);
        user.set("name", "stdout");

        // 评测程序
        JSONObject judge = new JSONObject();

        JSONObject judgeIn = new JSONObject();
        judgeIn.set("index", 1);
        judgeIn.set("fd", 1);

        JSONObject judgeOut = new JSONObject();
        judgeOut.set("index", 0);
        judgeOut.set("fd", 0);

        judge.set("in", judgeIn);
        judge.set("out", judgeOut);
        judge.set("max", STDIO_SIZE_MB * 1024 * 1024);
        judge.set("proxy", true);
        judge.set("name", "stdout");

        // 添加到管道映射列表
        pipeMapping.add(user);
        pipeMapping.add(judge);

        param.set("pipeMapping", pipeMapping);

        // 调用判题安全沙箱
        JSONArray result = INSTANCE.run("/run", param);
        JSONObject userRes = (JSONObject) result.get(0);
        JSONObject interactiveRes = (JSONObject) result.get(1);
        userRes.set("status", RESULT_STATUS_MAP.get(userRes.getStr("status")));
        interactiveRes.set("status", RESULT_STATUS_MAP.get(interactiveRes.getStr("status")));
        return result;
    }
}
/*
     1. compile
        Json Request Body
        {
            "cmd": [{
                "args": ["/usr/bin/g++", "a.cc", "-o", "a"],
                "env": ["PATH=/usr/bin:/bin"],
                "files": [{
                    "content": ""
                }, {
                    "name": "stdout",
                    "max": 10240
                }, {
                    "name": "stderr",
                    "max": 10240
                }],
                "cpuLimit": 10000000000,
                "memoryLimit": 104857600,
                "procLimit": 50,
                "copyIn": {
                    "a.cc": {
                        "content": "#include <iostream>\nusing namespace std;\nint main() {\nint a, b;\ncin >> a >> b;\ncout << a + b << endl;\n}"
                    }
                },
                "copyOut": ["stdout", "stderr"],
                "copyOutCached": ["a.cc", "a"],
                "copyOutDir": "1"
            }]
        }

        Json Response Data
        [
            {
                "status": "Accepted",
                "exitStatus": 0,
                "time": 303225231,
                "memory": 32243712,
                "runTime": 524177700,
                "files": {
                    "stderr": "",
                    "stdout": ""
                },
                "fileIds": {
                    "a": "5LWIZAA45JHX4Y4Z",
                    "a.cc": "NOHPGGDTYQUFRSLJ"
                }
            }
        ]
    2.test case

      Json Request Body
      {
        "cmd": [{
            "args": ["a"],
            "env": ["PATH=/usr/bin:/bin","LANG=en_US.UTF-8","LC_ALL=en_US.UTF-8","LANGUAGE=en_US:en"],
            "files": [{
                "src": "/judge/test_case/problem_1010/1.in"
            }, {
                "name": "stdout",
                "max": 10240
            }, {
                "name": "stderr",
                "max": 10240
            }],
            "cpuLimit": 10000000000,
            "realCpuLimit":30000000000,
            "stackLimit":134217728,
            "memoryLimit": 104811111,
            "procLimit": 50,
            "copyIn": {
                "a":{"fileId":"WDQL5TNLRRVB2KAP"}
            },
            "copyOut": ["stdout", "stderr"]
        }]
      }

    Json Response Data
     [{
      "status": "Accepted",
      "exitStatus": 0,
      "time": 3171607,
      "memory": 475136,
      "runTime": 110396333,
      "files": {
        "stderr": "",
        "stdout": "23\n"
      }
    }]

    3. Interactive
    {
        "pipeMapping": [{
            "in": {
                "max": 16777216,
                "index": 0,
                "fd": 1
            },
            "out": {
                "index": 1,
                "fd": 0
            }
        }],
        "cmd": [{
                "stackLimit": 134217728,
                "cpuLimit": 3000000000,
                "realCpuLimit": 9000000000,
                "clockLimit": 64,
                "env": [
                    "LANG=en_US.UTF-8",
                    "LANGUAGE=en_US:en",
                    "LC_ALL=en_US.UTF-8",
                    "PYTHONIOENCODING=utf-8"
                ],
                "copyOut": [
                    "stderr"
                ],
                "args": [
                    "/usr/bin/python3",
                    "main"
                ],
                "files": [{
                        "src": "/judge/test_case/problem_1002/5.in"
                    },
                    null,
                    {
                        "max": 16777216,
                        "name": "stderr"
                    }
                ],
                "memoryLimit": 536870912,
                "copyIn": {
                    "main": {
                        "fileId": "CGTRDEMKW5VAYN6O"
                    }
                }
            },
            {
                "stackLimit": 134217728,
                "cpuLimit": 8000000000,
                "clockLimit": 24000000000,
                "env": [
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=en_US.UTF-8",
                    "LANGUAGE=en_US:en",
                    "LC_ALL=en_US.UTF-8"
                ],
                "copyOut": [
                    "stdout",
                    "stderr"
                ],
                "args": [
                    "/w/spj",
                    "/w/tmp"
                ],
                "files": [
                    null,
                    {
                        "max": 16777216,
                        "name": "stdout"
                    },
                    {
                        "max": 16777216,
                        "name": "stderr"
                    }
                ],
                "memoryLimit": 536870912,
                "copyIn": {
                    "spj": {
                        "src": "/judge/spj/1002/spj"
                    },
                    "tmp": {
                        "src": "/judge/test_case/problem_1002/5.out"
                    }
                },
                "procLimit": 64
            }
        ]
    }
  */
