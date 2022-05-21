package com.simplefanc.voj.backend.service.file.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ZipUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.simplefanc.voj.backend.common.exception.StatusFailException;
import com.simplefanc.voj.backend.common.exception.StatusForbiddenException;
import com.simplefanc.voj.backend.common.utils.DownloadFileUtil;
import com.simplefanc.voj.backend.common.utils.ExcelUtil;
import com.simplefanc.voj.backend.dao.common.FileEntityService;
import com.simplefanc.voj.backend.dao.contest.ContestEntityService;
import com.simplefanc.voj.backend.dao.contest.ContestPrintEntityService;
import com.simplefanc.voj.backend.dao.contest.ContestProblemEntityService;
import com.simplefanc.voj.backend.dao.judge.JudgeEntityService;
import com.simplefanc.voj.backend.dao.user.UserInfoEntityService;
import com.simplefanc.voj.backend.pojo.bo.FilePathProps;
import com.simplefanc.voj.backend.pojo.vo.ACMContestRankVo;
import com.simplefanc.voj.backend.pojo.vo.OIContestRankVo;
import com.simplefanc.voj.backend.service.file.ContestFileService;
import com.simplefanc.voj.backend.service.oj.ContestCalculateRankService;
import com.simplefanc.voj.backend.validator.ContestValidator;
import com.simplefanc.voj.common.constants.ContestEnum;
import com.simplefanc.voj.common.constants.JudgeStatus;
import com.simplefanc.voj.common.pojo.entity.contest.Contest;
import com.simplefanc.voj.common.pojo.entity.contest.ContestPrint;
import com.simplefanc.voj.common.pojo.entity.contest.ContestProblem;
import com.simplefanc.voj.common.pojo.entity.judge.Judge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @Author: chenfan
 * @Date: 2022/3/10 14:27
 * @Description:
 */
@Service
@Slf4j(topic = "voj")
@RequiredArgsConstructor
public class ContestFileServiceImpl implements ContestFileService {

    private static final ThreadLocal<SimpleDateFormat> threadLocalTime = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyyMMddHHmmss");
        }
    };

    private final ContestEntityService contestEntityService;

    private final ContestProblemEntityService contestProblemEntityService;

    private final ContestPrintEntityService contestPrintEntityService;

    private final FileEntityService fileEntityService;

    private final JudgeEntityService judgeEntityService;

    private final UserInfoEntityService userInfoEntityService;

    private final ContestCalculateRankService contestCalculateRankService;

    private final ContestValidator contestValidator;

    private final FilePathProps filePathProps;

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    private static String languageToFileSuffix(String language) {

        List<String> CLang = Arrays.asList("c", "gcc", "clang");
        List<String> CPPLang = Arrays.asList("c++", "g++", "clang++");
        List<String> PythonLang = Arrays.asList("python", "pypy");

        for (String lang : CPPLang) {
            if (language.contains(lang)) {
                return "cpp";
            }
        }

        if (language.contains("c#")) {
            return "cs";
        }

        for (String lang : CLang) {
            if (language.contains(lang)) {
                return "c";
            }
        }

        for (String lang : PythonLang) {
            if (language.contains(lang)) {
                return "py";
            }
        }

        if (language.contains("javascript")) {
            return "js";
        }

        if (language.contains("java")) {
            return "java";
        }

        if (language.contains("pascal")) {
            return "pas";
        }

        if (language.contains("go")) {
            return "go";
        }

        if (language.contains("php")) {
            return "php";
        }

        return "txt";
    }

    @Override
    public void downloadContestRank(Long cid, Boolean forceRefresh, Boolean removeStar, HttpServletResponse response)
            throws IOException {
        // 获取本场比赛的状态
        Contest contest = contestEntityService.getById(cid);

        if (contest == null) {
            throw new StatusFailException("错误：该比赛不存在！");
        }

        if (!contestValidator.isContestAdmin(contest)) {
            throw new StatusForbiddenException("错误：您并非该比赛的管理员，无权下载榜单！");
        }

        // 检查是否开启封榜模式
        boolean isOpenSealRank = contestValidator.isOpenSealRank(contest, forceRefresh);
        final String fileName = "contest_" + contest.getId() + "_rank";

        ExcelUtil.wrapExcelResponse(response, fileName);

        // 获取题目displayID列表
        QueryWrapper<ContestProblem> contestProblemQueryWrapper = new QueryWrapper<>();
        contestProblemQueryWrapper.eq("cid", contest.getId()).select("display_id").orderByAsc("display_id");
        List<String> contestProblemDisplayIdList = contestProblemEntityService.list(contestProblemQueryWrapper).stream()
                .map(ContestProblem::getDisplayId).collect(Collectors.toList());

        // ACM比赛
        if (contest.getType().intValue() == ContestEnum.TYPE_ACM.getCode()) {
            List<ACMContestRankVo> acmContestRankVoList = contestCalculateRankService.calculateACMRank(isOpenSealRank,
                    removeStar, contest, null, null);
            EasyExcel.write(response.getOutputStream())
                    .head(fileEntityService.getContestRankExcelHead(contestProblemDisplayIdList, true)).sheet("rank")
                    .doWrite(fileEntityService.changeACMContestRankToExcelRowList(acmContestRankVoList,
                            contestProblemDisplayIdList, contest.getRankShowName()));
        } else {
            List<OIContestRankVo> oiContestRankVoList = contestCalculateRankService.calculateOIRank(isOpenSealRank,
                    removeStar, contest, null, null);
            EasyExcel.write(response.getOutputStream())
                    .head(fileEntityService.getContestRankExcelHead(contestProblemDisplayIdList, false)).sheet("rank")
                    .doWrite(fileEntityService.changeOIContestRankToExcelRowList(oiContestRankVoList,
                            contestProblemDisplayIdList, contest.getRankShowName()));
        }
    }

    @Override
    public void downloadContestAcSubmission(Long cid, Boolean excludeAdmin, String splitType,
                                            HttpServletResponse response) {

        Contest contest = contestEntityService.getById(cid);

        if (contest == null) {
            throw new StatusFailException("错误：该比赛不存在！");
        }

        if (!contestValidator.isContestAdmin(contest)) {
            throw new StatusForbiddenException("错误：您并非该比赛的管理员，无权下载AC记录！");
        }

        boolean isACM = contest.getType().intValue() == ContestEnum.TYPE_ACM.getCode();

        QueryWrapper<ContestProblem> contestProblemQueryWrapper = new QueryWrapper<>();
        contestProblemQueryWrapper.eq("cid", contest.getId());
        List<ContestProblem> contestProblemList = contestProblemEntityService.list(contestProblemQueryWrapper);

        List<String> superAdminUidList = userInfoEntityService.getSuperAdminUidList();

        QueryWrapper<Judge> judgeQueryWrapper = new QueryWrapper<>();
        judgeQueryWrapper.eq("cid", cid).eq(isACM, "status", JudgeStatus.STATUS_ACCEPTED.getStatus())
                // OI模式取得分不为null的
                .isNotNull(!isACM, "score").between("submit_time", contest.getStartTime(), contest.getEndTime())
                // 排除比赛创建者和root
                .ne(excludeAdmin, "uid", contest.getUid())
                .notIn(excludeAdmin && superAdminUidList.size() > 0, "uid", superAdminUidList)
                .orderByDesc("submit_time");

        List<Judge> judgeList = judgeEntityService.list(judgeQueryWrapper);

        // 打包文件的临时路径 -> username为文件夹名字
        String tmpFilesDir = filePathProps.getContestAcSubmissionTmpFolder() + File.separator + IdUtil.fastSimpleUUID();
        FileUtil.mkdir(tmpFilesDir);

        HashMap<String, Boolean> recordMap = new HashMap<>();
        if ("user".equals(splitType)) {
            splitCodeByUser(isACM, contestProblemList, judgeList, tmpFilesDir, recordMap);
        } else if ("problem".equals(splitType)) {
            splitByProblem(isACM, contestProblemList, judgeList, tmpFilesDir, recordMap);
        }

        String zipFileName = "contest_" + contest.getId() + "_" + System.currentTimeMillis() + ".zip";
        String zipPath = filePathProps.getContestAcSubmissionTmpFolder() + File.separator + zipFileName;
        ZipUtil.zip(tmpFilesDir, zipPath);
        DownloadFileUtil.download(response, zipPath, zipFileName, "下载比赛AC代码失败，请重新尝试！");
        FileUtil.del(tmpFilesDir);
        FileUtil.del(zipPath);

    }

    /**
     * 以比赛题目编号来分割提交的代码
     */
    private void splitByProblem(boolean isACM, List<ContestProblem> contestProblemList, List<Judge> judgeList, String tmpFilesDir, HashMap<String, Boolean> recordMap) {
        for (ContestProblem contestProblem : contestProblemList) {
            // 对于每题目生成对应的文件夹
            String problemDir = tmpFilesDir + File.separator + contestProblem.getDisplayId();
            FileUtil.mkdir(problemDir);
            // 如果是ACM模式，则所有提交代码都要生成，如果同一题多次提交AC，加上提交时间秒后缀 ---> username_(666666).c
            // 如果是OI模式就生成最近一次提交即可，且带上分数 ---> username_(666666)_100.c
            List<Judge> problemSubmissionList = judgeList.stream()
                    // 过滤出对应题目的提交
                    .filter(judge -> judge.getPid().equals(contestProblem.getPid()))
                    // 根据提交时间进行降序
                    .sorted(Comparator.comparing(Judge::getSubmitTime).reversed()).collect(Collectors.toList());

            for (Judge judge : problemSubmissionList) {
                String filePath = problemDir + File.separator + judge.getUsername();
                if (!isACM) {
                    String key = judge.getUsername() + "_" + contestProblem.getDisplayId();
                    // OI模式只取最后一次提交
                    if (!recordMap.containsKey(key)) {
                        filePath += "_" + judge.getScore() + "_("
                                + threadLocalTime.get().format(judge.getSubmitTime()) + ")."
                                + languageToFileSuffix(judge.getLanguage().toLowerCase());
                        FileWriter fileWriter = new FileWriter(filePath);
                        fileWriter.write(judge.getCode());
                        recordMap.put(key, true);
                    }
                } else {
                    filePath += "_(" + threadLocalTime.get().format(judge.getSubmitTime()) + ")."
                            + languageToFileSuffix(judge.getLanguage().toLowerCase());
                    FileWriter fileWriter = new FileWriter(filePath);
                    fileWriter.write(judge.getCode());
                }
            }
        }
    }

    /**
     * 以用户来分割提交的代码
     */
    private void splitCodeByUser(boolean isACM, List<ContestProblem> contestProblemList, List<Judge> judgeList, String tmpFilesDir, HashMap<String, Boolean> recordMap) {
        List<String> usernameList = judgeList.stream()
                // 根据用户名过滤唯一
                .filter(distinctByKey(Judge::getUsername))
                // 映射出用户名列表
                .map(Judge::getUsername)
                .collect(Collectors.toList());

        HashMap<Long, String> cpIdMap = new HashMap<>();
        for (ContestProblem contestProblem : contestProblemList) {
            cpIdMap.put(contestProblem.getId(), contestProblem.getDisplayId());
        }

        for (String username : usernameList) {
            // 对于每个用户生成对应的文件夹
            String userDir = tmpFilesDir + File.separator + username;
            FileUtil.mkdir(userDir);
            // 如果是ACM模式，则所有提交代码都要生成，如果同一题多次提交AC，加上提交时间秒后缀 ---> A_(666666).c
            // 如果是OI模式就生成最近一次提交即可，且带上分数 ---> A_(666666)_100.c
            List<Judge> userSubmissionList = judgeList.stream()
                    // 过滤出对应用户的提交
                    .filter(judge -> judge.getUsername().equals(username))
                    // 根据提交时间进行降序
                    .sorted(Comparator.comparing(Judge::getSubmitTime).reversed()).collect(Collectors.toList());

            for (Judge judge : userSubmissionList) {
                String filePath = userDir + File.separator + cpIdMap.getOrDefault(judge.getCpid(), "null");

                // OI模式只取最后一次提交
                if (!isACM) {
                    String key = judge.getUsername() + "_" + judge.getPid();
                    if (!recordMap.containsKey(key)) {
                        filePath += "_" + judge.getScore() + "_("
                                + threadLocalTime.get().format(judge.getSubmitTime()) + ")."
                                + languageToFileSuffix(judge.getLanguage().toLowerCase());
                        FileWriter fileWriter = new FileWriter(filePath);
                        fileWriter.write(judge.getCode());
                        recordMap.put(key, true);
                    }

                } else {
                    filePath += "_(" + threadLocalTime.get().format(judge.getSubmitTime()) + ")."
                            + languageToFileSuffix(judge.getLanguage().toLowerCase());
                    FileWriter fileWriter = new FileWriter(filePath);
                    fileWriter.write(judge.getCode());
                }

            }
        }
    }

    @Override
    public void downloadContestPrintText(Long id, HttpServletResponse response) {
        ContestPrint contestPrint = contestPrintEntityService.getById(id);
        String filename = contestPrint.getUsername() + "_Contest_Print.txt";
        String filePath = filePathProps.getContestTextPrintFolder() + File.separator + id + File.separator + filename;
        if (!FileUtil.exist(filePath)) {
            FileWriter fileWriter = new FileWriter(filePath);
            fileWriter.write(contestPrint.getContent());
        }

        DownloadFileUtil.download(response, filePath, filename, "下载比赛打印文本文件失败，请重新尝试！");
    }

}