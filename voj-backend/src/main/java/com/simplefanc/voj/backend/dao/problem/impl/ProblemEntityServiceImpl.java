package com.simplefanc.voj.backend.dao.problem.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileReader;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simplefanc.voj.backend.dao.judge.JudgeEntityService;
import com.simplefanc.voj.backend.dao.problem.*;
import com.simplefanc.voj.backend.mapper.ProblemMapper;
import com.simplefanc.voj.backend.config.property.FilePathProperties;
import com.simplefanc.voj.backend.pojo.dto.ProblemDTO;
import com.simplefanc.voj.backend.pojo.vo.ImportProblemVO;
import com.simplefanc.voj.backend.pojo.vo.ProblemCountVO;
import com.simplefanc.voj.backend.pojo.vo.ProblemVO;
import com.simplefanc.voj.common.constants.Constant;
import com.simplefanc.voj.common.constants.ContestEnum;
import com.simplefanc.voj.common.constants.JudgeMode;
import com.simplefanc.voj.common.constants.ProblemEnum;
import com.simplefanc.voj.common.pojo.entity.problem.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @Author: chenfan
 * @since 2021-10-23
 */
@Service
@RequiredArgsConstructor
public class ProblemEntityServiceImpl extends ServiceImpl<ProblemMapper, Problem> implements ProblemEntityService {

    private final ProblemMapper problemMapper;

    private final JudgeEntityService judgeEntityService;

    private final ProblemCaseEntityService problemCaseEntityService;

    private final ProblemLanguageEntityService problemLanguageEntityService;

    private final TagEntityService tagEntityService;

    private final ProblemTagEntityService problemTagEntityService;

    private final ApplicationContext applicationContext;

    private final CodeTemplateEntityService codeTemplateEntityService;

    private final FilePathProperties filePathProps;

    // 去除每行末尾的空白符
    public static String rtrim(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[^\\S\\r\\n]+(?=\\n|\\r)|\\s+(?=$)", "");
    }

    @Override
    public Page<ProblemVO> getProblemList(int limit, int currentPage, String title, Integer difficulty,
                                          List<Long> tagIds, String oj, boolean allProblemVisible) {

        // 新建分页
        Page<ProblemVO> page = new Page<>(currentPage, limit);
        Integer tagListSize = null;
        if (tagIds != null) {
            tagIds = tagIds.stream().distinct().collect(Collectors.toList());
            tagListSize = tagIds.size();
        }

        List<ProblemVO> problemList = problemMapper.getProblemList(page, title, difficulty, tagIds, tagListSize, oj, allProblemVisible);

        if (problemList.size() > 0) {
            List<Long> pidList = problemList.stream().map(ProblemVO::getPid).collect(Collectors.toList());
            List<ProblemCountVO> problemListCount = judgeEntityService.getProblemListCount(pidList);
            for (ProblemVO problemVO : problemList) {
                for (ProblemCountVO problemCountVO : problemListCount) {
                    if (problemVO.getPid().equals(problemCountVO.getPid())) {
                        problemVO.setProblemCountVO(problemCountVO);
                        break;
                    }
                }
            }
        }

        return page.setRecords(problemList);
    }

    /**
     * 处理tag表与problem_tag表的删除与更新
     */
    public boolean processTag(Long pid, ProblemDTO problemDTO, String ojName) {
        Map<String, Object> map = new HashMap<>();
        map.put("pid", pid);
        // 与前端上传的数据进行对比，添加或删除！
        List<ProblemTag> oldProblemTags = (List<ProblemTag>) problemTagEntityService.listByMap(map);
        Map<Long, Integer> mapOldPT = new HashMap<>();
        // 登记一下原有的tag的id
        oldProblemTags.forEach(problemTag -> {
            mapOldPT.put(problemTag.getTid(), 0);
        });

        // 存储新的problem_tag表数据
        List<ProblemTag> problemTagList = new LinkedList<>();
        for (Tag tag : problemDTO.getTags()) {
            // 没有主键表示为新添加的标签
            if (tag.getId() == null) {
                tag.setOj(ojName);
                boolean addTagResult = tagEntityService.save(tag);
                if (addTagResult) {
                    problemTagList.add(new ProblemTag().setPid(pid).setTid(tag.getId()));
                }
                // 已存在tag 但是新添加的
            } else if (mapOldPT.getOrDefault(tag.getId(), null) == null) {
                problemTagList.add(new ProblemTag().setPid(pid).setTid(tag.getId()));
            } else {
                // 已有主键的需要记录一下，若原先在problem_tag有的，现在不见了，表明需要删除
                // 更新记录，说明该tag未删除
                mapOldPT.put(tag.getId(), 1);
            }
        }
        // 放入需要删除的tagId列表
        List<Long> needDeleteTids = new LinkedList<>();
        for (Long key : mapOldPT.keySet()) {
            // 记录表中没有更新原来的存在Tid，则表明该tag已不被该problem使用
            if (mapOldPT.get(key) == 0) {
                needDeleteTids.add(key);
            }
        }
        boolean deleteTagsFromProblemResult = true;
        if (needDeleteTids.size() > 0) {
            QueryWrapper<ProblemTag> tagWrapper = new QueryWrapper<>();
            tagWrapper.eq("pid", pid).in("tid", needDeleteTids);
            // 执行批量删除操作
            deleteTagsFromProblemResult = problemTagEntityService.remove(tagWrapper);
        }
        // 执行批量插入操作
        boolean addTagsToProblemResult = true;
        if (problemTagList.size() > 0) {
            addTagsToProblemResult = problemTagEntityService.saveOrUpdateBatch(problemTagList);
        }

        return deleteTagsFromProblemResult && addTagsToProblemResult;
    }


    /**
     * 处理problem_language表的更新与删除
     */
    public boolean processLanguage(Long pid, ProblemDTO problemDTO) {
        Map<String, Object> map = new HashMap<>();
        map.put("pid", pid);
        List<ProblemLanguage> oldProblemLanguages = (List<ProblemLanguage>) problemLanguageEntityService.listByMap(map);
        Map<Long, Integer> mapOldPL = new HashMap<>();
        // 登记一下原有的language的id
        oldProblemLanguages.forEach(problemLanguage -> {
            mapOldPL.put(problemLanguage.getLid(), 0);
        });
        // 根据上传来的language列表的每一个name字段查询对应的language表的id，更新problem_language
        // 构建problem_language实体列表
        List<ProblemLanguage> problemLanguageList = new LinkedList<>();
        // 遍历插入
        for (Language language : problemDTO.getLanguages()) {
            // 如果记录中有，则表式该language原来已有选中。
            if (mapOldPL.get(language.getId()) != null) {
                // 记录一下，新数据也有该language
                mapOldPL.put(language.getId(), 1);
            } else {
                // 没有记录，则表明为新添加的language
                problemLanguageList.add(new ProblemLanguage().setLid(language.getId()).setPid(pid));
            }
        }
        // 放入需要删除的languageId列表
        List<Long> needDeleteLids = new LinkedList<>();
        for (Long key : mapOldPL.keySet()) {
            // 记录表中没有更新原来的存在Lid，则表明该language已不被该problem使用
            if (mapOldPL.get(key) == 0) {
                needDeleteLids.add(key);
            }
        }
        boolean deleteLanguagesFromProblemResult = true;
        if (needDeleteLids.size() > 0) {
            QueryWrapper<ProblemLanguage> LangWrapper = new QueryWrapper<>();
            LangWrapper.eq("pid", pid).in("lid", needDeleteLids);
            // 执行批量删除操作
            deleteLanguagesFromProblemResult = problemLanguageEntityService.remove(LangWrapper);
        }
        // 执行批量添加操作
        boolean addLanguagesToProblemResult = true;
        if (problemLanguageList.size() > 0) {
            addLanguagesToProblemResult = problemLanguageEntityService.saveOrUpdateBatch(problemLanguageList);
        }

        return deleteLanguagesFromProblemResult && addLanguagesToProblemResult;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean adminUpdateProblem(ProblemDTO problemDTO) {
        Problem problem = problemDTO.getProblem();
        if (JudgeMode.DEFAULT.getMode().equals(problemDTO.getJudgeMode())) {
            problem.setSpjLanguage(null).setSpjCode(null);
        }

        String ojName = Constant.LOCAL;
        if (problem.getIsRemote()) {
            String problemId = problem.getProblemId();
            ojName = problemId.split("-")[0];
        }

        long pid = checkUniquePid(problemDTO, problem);

        if (problemMapper.updateById(problem) == 1 &&
                processTag(pid, problemDTO, ojName) &&
                processCodeTemplate(pid, problemDTO) &&
                processLanguage(pid, problemDTO) &&
                // 处理problem_case表的增加与删除
                processProblemCase(pid, problemDTO, problem)) {
            return true;
        }
        return false;
    }

    /**
     * 处理problem_case表的增加与删除
     */
    public boolean processProblemCase(long pid, ProblemDTO problemDTO, Problem problem) {
        Map<String, Object> map = new HashMap<>();
        map.put("pid", pid);
        List<ProblemCase> oldProblemCases = (List<ProblemCase>) problemCaseEntityService.listByMap(map);
        HashMap<Long, ProblemCase> oldProblemMap = new HashMap<>();
        List<Long> needDeleteProblemCases = new LinkedList<>();
        // 登记一下原有的case的id
        oldProblemCases.forEach(problemCase -> {
            needDeleteProblemCases.add(problemCase.getId());
            oldProblemMap.put(problemCase.getId(), problemCase);
        });
        boolean checkProblemCase = true;
        // 如果是自家的题目才有测试数据
        if (!problem.getIsRemote() && problemDTO.getSamples().size() > 0) {
            // 新增加的case列表
            List<ProblemCase> newProblemCaseList = new LinkedList<>();
            // 需要修改的case列表
            List<ProblemCase> needUpdateProblemCaseList = new LinkedList<>();
            // 遍历上传的case列表，如果还存在，则从需要删除的测试样例列表移除该id
            for (ProblemCase problemCase : problemDTO.getSamples()) {
                // 已存在的case
                if (problemCase.getId() != null) {
                    needDeleteProblemCases.remove(problemCase.getId());
                    // 跟原先的数据做对比，如果变动 则加入需要修改的case列表
                    ProblemCase oldProblemCase = oldProblemMap.get(problemCase.getId());
                    if (!oldProblemCase.getInput().equals(problemCase.getInput())
                            || !oldProblemCase.getOutput().equals(problemCase.getOutput())) {
                        needUpdateProblemCaseList.add(problemCase);
                    } else if (problem.getType().intValue() == ContestEnum.TYPE_OI.getCode()) {
                        // 分数变动
                        if (!Objects.equals(oldProblemCase.getScore(), problemCase.getScore())) {
                            needUpdateProblemCaseList.add(problemCase);
                        }
                    }
                } else {
                    newProblemCaseList.add(problemCase.setPid(pid));
                }
            }
            // 执行批量删除操作
            boolean deleteCasesFromProblemResult = true;
            if (needDeleteProblemCases.size() > 0) {
                deleteCasesFromProblemResult = problemCaseEntityService.removeByIds(needDeleteProblemCases);
            }
            // 执行批量添加操作
            boolean addCasesToProblemResult = true;
            if (newProblemCaseList.size() > 0) {
                addCasesToProblemResult = problemCaseEntityService.saveBatch(newProblemCaseList);
            }
            // 执行批量修改操作
            boolean updateCasesToProblemResult = true;
            if (needUpdateProblemCaseList.size() > 0) {
                updateCasesToProblemResult = problemCaseEntityService.saveOrUpdateBatch(needUpdateProblemCaseList);
            }
            checkProblemCase = addCasesToProblemResult && deleteCasesFromProblemResult && updateCasesToProblemResult;


            // 只要有新添加，修改，删除都需要更新版本号 同时更新测试数据
            String caseVersion = String.valueOf(System.currentTimeMillis());
            String testcaseDir = problemDTO.getUploadTestcaseDir();
            if (needDeleteProblemCases.size() > 0 || newProblemCaseList.size() > 0
                    || needUpdateProblemCaseList.size() > 0 || StrUtil.isNotEmpty(testcaseDir)) {
                problem.setCaseVersion(caseVersion);
                // 如果是选择上传测试文件的，则需要遍历对应文件夹，读取数据，写入数据库,先前的题目数据一并清空。
                if (problemDTO.getIsUploadTestCase()) {
                    // 获取代理bean对象执行异步方法===》根据测试文件初始info
                    applicationContext.getBean(ProblemEntityServiceImpl.class).initUploadTestCase(
                            problemDTO.getJudgeMode(), caseVersion, pid, testcaseDir, problemDTO.getSamples());
                } else {
                    applicationContext.getBean(ProblemEntityServiceImpl.class).initHandTestCase(
                            problemDTO.getJudgeMode(), problem.getCaseVersion(), pid, problemDTO.getSamples());
                }
            } else if (problemDTO.getChangeModeCode() != null && problemDTO.getChangeModeCode()) {
                // 变化成spj或interactive或者取消 同时更新测试数据
                problem.setCaseVersion(caseVersion);
                if (problemDTO.getIsUploadTestCase()) {
                    // 获取代理bean对象执行异步方法===》根据测试文件初始info
                    applicationContext.getBean(ProblemEntityServiceImpl.class).initUploadTestCase(
                            problemDTO.getJudgeMode(), caseVersion, pid, null, problemDTO.getSamples());
                } else {
                    applicationContext.getBean(ProblemEntityServiceImpl.class).initHandTestCase(
                            problemDTO.getJudgeMode(), problem.getCaseVersion(), pid, problemDTO.getSamples());
                }
            }
        }
        return checkProblemCase;
    }


    /**
     * 处理code_template表
     */
    public boolean processCodeTemplate(Long pid, ProblemDTO problemDTO) {
        Map<String, Object> map = new HashMap<>();
        map.put("pid", pid);
        List<CodeTemplate> oldProblemTemplate = (List<CodeTemplate>) codeTemplateEntityService.listByMap(map);
        Map<Integer, Integer> mapOldPCT = new HashMap<>();
        // 登记一下原有的codeTemplate的id
        oldProblemTemplate.forEach(codeTemplate -> {
            mapOldPCT.put(codeTemplate.getId(), 0);
        });
        boolean deleteTemplate = true;
        boolean saveOrUpdateCodeTemplate = true;
        for (CodeTemplate codeTemplate : problemDTO.getCodeTemplates()) {
            if (codeTemplate.getId() != null) {
                mapOldPCT.put(codeTemplate.getId(), 1);
            }
        }
        // 需要删除的模板
        List<Integer> needDeleteCTs = new LinkedList<>();
        for (Integer key : mapOldPCT.keySet()) {
            if (mapOldPCT.get(key) == 0) {
                needDeleteCTs.add(key);
            }
        }
        if (needDeleteCTs.size() > 0) {
            deleteTemplate = codeTemplateEntityService.removeByIds(needDeleteCTs);
        }
        if (problemDTO.getCodeTemplates().size() > 0) {
            saveOrUpdateCodeTemplate = codeTemplateEntityService.saveOrUpdateBatch(problemDTO.getCodeTemplates());
        }
        return deleteTemplate && saveOrUpdateCodeTemplate;
    }

    /**
     * problem_id唯一性检查
     */
    public long checkUniquePid(ProblemDTO problemDTO, Problem problem) {
        String problemId = problem.getProblemId().toUpperCase();
        QueryWrapper<Problem> problemQueryWrapper = new QueryWrapper<>();
        problemQueryWrapper.eq("problem_id", problemId);
        Problem existedProblem = problemMapper.selectOne(problemQueryWrapper);

        problem.setProblemId(problem.getProblemId().toUpperCase());
        // 后面许多表的更新或删除需要用到题目id
        long pid = problemDTO.getProblem().getId();

        if (existedProblem != null && existedProblem.getId() != pid) {
            throw new RuntimeException("The problem_id [" + problemId + "] already exists. Do not reuse it!");
        }
        return pid;
    }

    // TODO 行数过多
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean adminAddProblem(ProblemDTO problemDTO) {
        Problem problem = problemDTO.getProblem();
        
        // problem_id唯一性检查
        String problemId = problem.getProblemId().toUpperCase();
        QueryWrapper<Problem> problemQueryWrapper = new QueryWrapper<>();
        problemQueryWrapper.eq("problem_id", problemId);
        int existedProblem = problemMapper.selectCount(problemQueryWrapper);
        if (existedProblem > 0) {
            throw new RuntimeException("The problem_id [" + problemId + "] already exists. Do not reuse it!");
        }

        problem.setProblemId(problemId);
        problem.setCaseVersion(String.valueOf(System.currentTimeMillis()));
        if (JudgeMode.DEFAULT.getMode().equals(problemDTO.getJudgeMode())) {
            problem.setSpjLanguage(null).setSpjCode(null);
        }

        // 1. 插入到数据库
        boolean addProblemResult = problemMapper.insert(problem) == 1;
        long pid = problem.getId();

        // 2. 为新的题目添加对应的language
        List<ProblemLanguage> problemLanguageList = new LinkedList<>();
        for (Language language : problemDTO.getLanguages()) {
            problemLanguageList.add(new ProblemLanguage().setPid(pid).setLid(language.getId()));
        }
        boolean addLangToProblemResult = problemLanguageEntityService.saveOrUpdateBatch(problemLanguageList);

        // 3. 为新的题目添加对应的codeTemplate
        boolean addProblemCodeTemplate = true;
        if (problemDTO.getCodeTemplates() != null && problemDTO.getCodeTemplates().size() > 0) {
            for (CodeTemplate codeTemplate : problemDTO.getCodeTemplates()) {
                codeTemplate.setPid(pid);
            }
            addProblemCodeTemplate = codeTemplateEntityService.saveOrUpdateBatch(problemDTO.getCodeTemplates());
        }

        // 4. 为新的题目添加对应的case
        boolean addCasesToProblemResult = addCasesToProblem(problemDTO, problem, pid);

        // 5. 为新的题目添加对应的tag，可能tag是原表已有，也可能是新的，所以需要判断。
        List<ProblemTag> problemTagList = new LinkedList<>();
        if (problemDTO.getTags() != null) {
            for (Tag tag : problemDTO.getTags()) {
                // id为空 表示为原tag表中不存在的 插入后可以获取到对应的tagId
                if (tag.getId() == null) {
                    tag.setOj(Constant.LOCAL);
                    try {
                        tagEntityService.save(tag);
                    } catch (Exception ignored) {
                        tag = tagEntityService
                                .getOne(new QueryWrapper<Tag>().eq("name", tag.getName()).eq("oj", Constant.LOCAL), false);
                    }
                }
                problemTagList.add(new ProblemTag().setTid(tag.getId()).setPid(pid));
            }
        }
        boolean addTagsToProblemResult = true;
        if (problemTagList.size() > 0) {
            addTagsToProblemResult = problemTagEntityService.saveOrUpdateBatch(problemTagList);
        }

        if (addProblemResult && addCasesToProblemResult && addLangToProblemResult && addTagsToProblemResult
                && addProblemCodeTemplate) {
            return true;
        }
        return false;
    }

    private boolean addCasesToProblem(ProblemDTO problemDTO, Problem problem, long pid) {
        boolean addCasesToProblemResult;
        // 为新的题目添加对应的case
        // 如果是选择上传测试文件的，则需要遍历对应文件夹，读取数据。
        if (problemDTO.getIsUploadTestCase()) {
            String testcaseDir = problemDTO.getUploadTestcaseDir();
            // 如果是oi题目统计总分
            List<ProblemCase> problemCases = problemDTO.getSamples();
            if (problemCases.size() == 0) {
                throw new RuntimeException("The test cases of problem must not be empty!");
            }
            for (ProblemCase problemCase : problemCases) {
                if (StrUtil.isEmpty(problemCase.getOutput())) {
                    String filePreName = problemCase.getInput().split("\\.")[0];
                    problemCase.setOutput(filePreName + ".out");
                }
                problemCase.setPid(pid);
            }
            addCasesToProblemResult = problemCaseEntityService.saveOrUpdateBatch(problemCases);
            // 获取代理bean对象 执行异步方法 ===》根据测试文件初始info
            applicationContext.getBean(ProblemEntityServiceImpl.class).initUploadTestCase(problemDTO.getJudgeMode(),
                    problem.getCaseVersion(), pid, testcaseDir, problemDTO.getSamples());
        } else {
            // oi题目需要求取平均值，给每个测试点初始oi的score值，默认总分100分
            if (problem.getType().intValue() == ContestEnum.TYPE_OI.getCode()) {
                final int averScore = 100 / problemDTO.getSamples().size();
                // 设置好新题目的pid及分数
                problemDTO.getSamples().forEach(problemCase -> problemCase.setPid(pid).setScore(averScore));
                addCasesToProblemResult = problemCaseEntityService.saveOrUpdateBatch(problemDTO.getSamples());
            } else {
                // 设置好新题目的pid
                problemDTO.getSamples().forEach(problemCase -> problemCase.setPid(pid));
                addCasesToProblemResult = problemCaseEntityService.saveOrUpdateBatch(problemDTO.getSamples());
            }
            initHandTestCase(problemDTO.getJudgeMode(), problem.getCaseVersion(), pid, problemDTO.getSamples());
        }
        return addCasesToProblemResult;
    }

    /**
     * 初始化上传文件的测试数据，写成json文件
     * @param mode
     * @param version
     * @param problemId
     * @param tmpTestcaseDir
     * @param problemCaseList
     */
    @Async
    public void initUploadTestCase(String mode, String version, Long problemId, String tmpTestcaseDir,
                                   List<ProblemCase> problemCaseList) {

        String testCasesDir = filePathProps.getTestcaseBaseFolder() + File.separator + "problem_" + problemId;

        // 将之前的临时文件夹里面的评测文件全部复制到指定文件夹(覆盖)
        if (StrUtil.isNotEmpty(tmpTestcaseDir)) {
            FileUtil.clean(testCasesDir);
            FileUtil.copyFilesFromDir(new File(tmpTestcaseDir), new File(testCasesDir), true);
        }

        JSONObject result = new JSONObject();
        result.set("mode", mode);
        result.set("version", version);
        result.set("testCasesSize", problemCaseList.size());

        JSONArray testCaseList = new JSONArray(problemCaseList.size());

        for (ProblemCase problemCase : problemCaseList) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.set("caseId", problemCase.getId());
            jsonObject.set("score", problemCase.getScore());
            jsonObject.set("inputName", problemCase.getInput());
            jsonObject.set("outputName", problemCase.getOutput());

            // 读取输入文件
            FileReader inputFile = new FileReader(testCasesDir + File.separator + problemCase.getInput(),
                    CharsetUtil.UTF_8);
            String input = inputFile.readString().replaceAll("\r\n", "\n");

            FileWriter inputFileWriter = new FileWriter(testCasesDir + File.separator + problemCase.getInput(),
                    CharsetUtil.UTF_8);
            inputFileWriter.write(input);

            // 读取输出文件
            FileReader outputFile = new FileReader(testCasesDir + File.separator + problemCase.getOutput(),
                    CharsetUtil.UTF_8);
            String output = outputFile.readString().replaceAll("\r\n", "\n");

            FileWriter outFileWriter = new FileWriter(testCasesDir + File.separator + problemCase.getOutput(),
                    CharsetUtil.UTF_8);
            outFileWriter.write(output);

            // spj和interactive是根据特判程序输出判断结果，所以无需初始化测试数据
            if (JudgeMode.DEFAULT.getMode().equals(mode)) {
                // 原数据MD5
                jsonObject.set("outputMd5", DigestUtils.md5DigestAsHex(output.getBytes()));
                // 原数据大小
                jsonObject.set("outputSize", output.getBytes().length);
                // 去掉全部空格的MD5，用来判断pe
                jsonObject.set("allStrippedOutputMd5",
                        DigestUtils.md5DigestAsHex(output.replaceAll("\\s+", "").getBytes()));
                // 默认去掉文末空格的MD5
                jsonObject.set("EOFStrippedOutputMd5", DigestUtils.md5DigestAsHex(rtrim(output).getBytes()));
            }

            testCaseList.add(jsonObject);
        }

        result.set("testCases", testCaseList);

        FileWriter infoFile = new FileWriter(testCasesDir + "/info", CharsetUtil.UTF_8);
        // 写入记录文件
        infoFile.write(JSONUtil.toJsonStr(result));
        // 删除临时上传文件夹
        FileUtil.del(tmpTestcaseDir);
    }

    /**
     * 初始化手动输入上传的测试数据，写成json文件
     * @param mode
     * @param version
     * @param problemId
     * @param problemCaseList
     */
    @Async
    public void initHandTestCase(String mode, String version, Long problemId, List<ProblemCase> problemCaseList) {

        JSONObject result = new JSONObject();
        result.set("mode", mode);
        result.set("version", version);
        result.set("testCasesSize", problemCaseList.size());

        JSONArray testCaseList = new JSONArray(problemCaseList.size());

        String testCasesDir = filePathProps.getTestcaseBaseFolder() + File.separator + "problem_" + problemId;
        FileUtil.del(testCasesDir);
        for (int index = 0; index < problemCaseList.size(); index++) {
            JSONObject jsonObject = new JSONObject();
            String inputName = (index + 1) + ".in";
            jsonObject.set("caseId", problemCaseList.get(index).getId());
            jsonObject.set("score", problemCaseList.get(index).getScore());
            jsonObject.set("inputName", inputName);
            // 生成对应文件
            FileWriter infileWriter = new FileWriter(testCasesDir + "/" + inputName, CharsetUtil.UTF_8);
            // 将该测试数据的输入写入到文件
            String inputData = problemCaseList.get(index).getInput().replaceAll("\r\n", "\n");
            infileWriter.write(inputData);

            String outputName = (index + 1) + ".out";
            jsonObject.set("outputName", outputName);
            // 生成对应文件
            String outputData = problemCaseList.get(index).getOutput().replaceAll("\r\n", "\n");
            FileWriter outFile = new FileWriter(testCasesDir + "/" + outputName, CharsetUtil.UTF_8);
            outFile.write(outputData);

            // spj和interactive是根据特判程序输出判断结果，所以无需初始化测试数据
            if (JudgeMode.DEFAULT.getMode().equals(mode)) {
                // 原数据MD5
                jsonObject.set("outputMd5", DigestUtils.md5DigestAsHex(outputData.getBytes()));
                // 原数据大小
                try {
                    jsonObject.set("outputSize", outputData.getBytes(CharsetUtil.UTF_8).length);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                // 去掉全部空格的MD5，用来判断pe
                jsonObject.set("allStrippedOutputMd5",
                        DigestUtils.md5DigestAsHex(outputData.replaceAll("\\s+", "").getBytes()));
                // 默认去掉文末空格的MD5
                jsonObject.set("EOFStrippedOutputMd5", DigestUtils.md5DigestAsHex(rtrim(outputData).getBytes()));
            }

            testCaseList.add(jsonObject);
        }

        result.set("testCases", testCaseList);

        FileWriter infoFile = new FileWriter(testCasesDir + "/info", CharsetUtil.UTF_8);
        // 写入记录文件
        infoFile.write(JSONUtil.toJsonStr(result));
    }

    @Override
    // 如果是有提交记录的
    @SuppressWarnings("All")
    public ImportProblemVO buildExportProblem(Long pid, List<HashMap<String, Object>> problemCaseList,
                                              HashMap<Long, String> languageMap, HashMap<Long, String> tagMap) {
        // TODO 参数
        // 导出相当于导入
        ImportProblemVO importProblemVO = new ImportProblemVO();
        Problem problem = problemMapper.selectById(pid);
        problem.setCaseVersion(null).setGmtCreate(null).setId(null).setAuth(ProblemEnum.AUTH_PUBLIC.getCode()).setIsUploadCase(true).setAuthor(null)
                .setGmtModified(null);
        HashMap<String, Object> problemMap = new HashMap<>();
        BeanUtil.beanToMap(problem, problemMap, false, true);
        importProblemVO.setProblem(problemMap);
        QueryWrapper<CodeTemplate> codeTemplateQueryWrapper = new QueryWrapper<>();
        codeTemplateQueryWrapper.eq("pid", pid).eq("status", true);
        List<CodeTemplate> codeTemplates = codeTemplateEntityService.list(codeTemplateQueryWrapper);
        List<HashMap<String, String>> codeTemplateList = new LinkedList<>();
        for (CodeTemplate codeTemplate : codeTemplates) {
            HashMap<String, String> tmp = new HashMap<>();
            tmp.put("language", languageMap.get(codeTemplate.getLid()));
            tmp.put("code", codeTemplate.getCode());
            codeTemplateList.add(tmp);
        }
        importProblemVO.setCodeTemplates(codeTemplateList);
        importProblemVO.setJudgeMode(problem.getJudgeMode());
        importProblemVO.setSamples(problemCaseList);

        if (StrUtil.isNotEmpty(problem.getUserExtraFile())) {
            HashMap<String, String> userExtraFileMap = (HashMap<String, String>) JSONUtil
                    .toBean(problem.getUserExtraFile(), Map.class);
            importProblemVO.setUserExtraFile(userExtraFileMap);
        }

        if (StrUtil.isNotEmpty(problem.getJudgeExtraFile())) {
            HashMap<String, String> judgeExtraFileMap = (HashMap<String, String>) JSONUtil
                    .toBean(problem.getJudgeExtraFile(), Map.class);
            importProblemVO.setUserExtraFile(judgeExtraFileMap);
        }

        QueryWrapper<ProblemTag> problemTagQueryWrapper = new QueryWrapper<>();
        problemTagQueryWrapper.eq("pid", pid);
        List<ProblemTag> problemTags = problemTagEntityService.list(problemTagQueryWrapper);
        importProblemVO.setTags(
                problemTags.stream().map(problemTag -> tagMap.get(problemTag.getTid())).collect(Collectors.toList()));

        QueryWrapper<ProblemLanguage> problemLanguageQueryWrapper = new QueryWrapper<>();
        problemLanguageQueryWrapper.eq("pid", pid);
        List<ProblemLanguage> problemLanguages = problemLanguageEntityService.list(problemLanguageQueryWrapper);
        importProblemVO.setLanguages(problemLanguages.stream()
                .map(problemLanguage -> languageMap.get(problemLanguage.getLid())).collect(Collectors.toList()));

        return importProblemVO;
    }

}
