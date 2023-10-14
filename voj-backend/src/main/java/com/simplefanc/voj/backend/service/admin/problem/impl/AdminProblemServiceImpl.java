package com.simplefanc.voj.backend.service.admin.problem.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simplefanc.voj.backend.common.constants.CallJudgerType;
import com.simplefanc.voj.backend.common.exception.StatusFailException;
import com.simplefanc.voj.backend.common.exception.StatusForbiddenException;
import com.simplefanc.voj.backend.dao.judge.JudgeEntityService;
import com.simplefanc.voj.backend.dao.problem.ProblemCaseEntityService;
import com.simplefanc.voj.backend.dao.problem.ProblemEntityService;
import com.simplefanc.voj.backend.judge.Dispatcher;
import com.simplefanc.voj.backend.judge.remote.crawler.AbstractProblemCrawler;
import com.simplefanc.voj.backend.config.property.FilePathProperties;
import com.simplefanc.voj.backend.pojo.dto.ProblemDTO;
import com.simplefanc.voj.backend.pojo.vo.UserRolesVO;
import com.simplefanc.voj.backend.service.admin.problem.AdminProblemService;
import com.simplefanc.voj.backend.service.admin.problem.RemoteProblemService;
import com.simplefanc.voj.backend.shiro.UserSessionUtil;
import com.simplefanc.voj.common.constants.ProblemEnum;
import com.simplefanc.voj.common.constants.RemoteOj;
import com.simplefanc.voj.common.pojo.dto.CompileDTO;
import com.simplefanc.voj.common.pojo.entity.judge.Judge;
import com.simplefanc.voj.common.pojo.entity.problem.Problem;
import com.simplefanc.voj.common.pojo.entity.problem.ProblemCase;
import com.simplefanc.voj.common.result.CommonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;

/**
 * @Author: chenfan
 * @Date: 2022/3/9 16:32
 * @Description:
 */

@Service
@RefreshScope
@RequiredArgsConstructor
@Slf4j(topic = "voj")
public class AdminProblemServiceImpl implements AdminProblemService {

    private final ProblemEntityService problemEntityService;

    private final ProblemCaseEntityService problemCaseEntityService;

    private final Dispatcher dispatcher;

    @Value("${voj.judge.token}")
    private String judgeToken;

    private final JudgeEntityService judgeEntityService;

    private final RemoteProblemService remoteProblemService;

    private final FilePathProperties filePathProps;

    @Override
    public IPage<Problem> getProblemList(Integer limit, Integer currentPage, String keyword, Integer auth, String oj) {
        if (currentPage == null || currentPage < 1) {
            currentPage = 1;
        }
        if (limit == null || limit < 1) {
            limit = 10;
        }
        IPage<Problem> iPage = new Page<>(currentPage, limit);
        IPage<Problem> problemList;

        QueryWrapper<Problem> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("gmt_create").orderByDesc("id");

        // 根据oj筛选过滤
        if (oj != null && !"All".equals(oj)) {
            if (!RemoteOj.isRemoteOj(oj)) {
                queryWrapper.eq("is_remote", false);
            } else {
                queryWrapper.eq("is_remote", true).likeRight("problem_id", oj);
            }
        }

        if (auth != null && auth != 0) {
            queryWrapper.eq("auth", auth);
        }

        if (StrUtil.isNotEmpty(keyword)) {
            final String key = keyword.trim();
            queryWrapper
                    .and(wrapper -> wrapper.like("title", key).or().like("author", key).or().like("problem_id", key));
        }
        problemList = problemEntityService.page(iPage, queryWrapper);
        return problemList;
    }

    @Override
    public Problem getProblem(Long pid) {
        Problem problem = problemEntityService.getById(pid);

        // 查询成功
        if (problem != null) {
            // 获取当前登录的用户
            UserRolesVO userRolesVO = UserSessionUtil.getUserInfo();

            boolean isRoot = UserSessionUtil.isRoot();
            boolean isProblemAdmin = UserSessionUtil.isProblemAdmin();
            // 只有超级管理员和题目管理员、题目创建者才能操作
            if (!isRoot && !isProblemAdmin && !userRolesVO.getUsername().equals(problem.getAuthor())) {
                throw new StatusForbiddenException("对不起，你无权限查看题目！");
            }

            return problem;
        } else {
            throw new StatusFailException("查询失败！");
        }
    }

    @Override
    public void deleteProblem(Long pid) {
        boolean isOk = problemEntityService.removeById(pid);
        // problem的id为其他表的外键的表中的对应数据都会被一起删除！
        // 删除成功
        if (isOk) {
            FileUtil.del(filePathProps.getTestcaseBaseFolder() + File.separator + "problem_" + pid);
        } else {
            throw new StatusFailException("删除失败！");
        }
    }

    @Override
    public void addProblem(ProblemDTO problemDTO) {
        QueryWrapper<Problem> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("problem_id", problemDTO.getProblem().getProblemId().toUpperCase());
        Problem problem = problemEntityService.getOne(queryWrapper);
        if (problem != null) {
            throw new StatusFailException("该题目的Problem ID 已存在，请更换！");
        }

        boolean isOk = problemEntityService.adminAddProblem(problemDTO);
        if (!isOk) {
            throw new StatusFailException("添加失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProblem(ProblemDTO problemDTO) {
        // 获取当前登录的用户
        UserRolesVO userRolesVO = UserSessionUtil.getUserInfo();

        boolean isRoot = UserSessionUtil.isRoot();
        boolean isProblemAdmin = UserSessionUtil.isProblemAdmin();
        // 只有超级管理员和题目管理员、题目创建者才能操作
        if (!isRoot && !isProblemAdmin && !userRolesVO.getUsername().equals(problemDTO.getProblem().getAuthor())) {
            throw new StatusForbiddenException("对不起，你无权限修改题目！");
        }

        String problemId = problemDTO.getProblem().getProblemId().toUpperCase();
        QueryWrapper<Problem> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("problem_id", problemId);
        Problem problem = problemEntityService.getOne(queryWrapper);

        // 如果problem_id不是原来的且已存在该problem_id，则修改失败！
        if (problem != null && problem.getId().longValue() != problemDTO.getProblem().getId()) {
            throw new StatusFailException("当前的Problem ID 已被使用，请更换！");
        }

        // 记录修改题目的用户
        problemDTO.getProblem().setModifiedUser(userRolesVO.getUsername());

        boolean result = problemEntityService.adminUpdateProblem(problemDTO);
        // 更新成功
        if (result) {
            // 说明改了problemId，同步一下judge表
            if (problem == null) {
                UpdateWrapper<Judge> judgeUpdateWrapper = new UpdateWrapper<>();
                judgeUpdateWrapper.eq("pid", problemDTO.getProblem().getId()).set("display_pid", problemId);
                judgeEntityService.update(judgeUpdateWrapper);
            }

        } else {
            throw new StatusFailException("修改失败");
        }
    }

    @Override
    public List<ProblemCase> getProblemCases(Long pid, Boolean isUpload) {
        QueryWrapper<ProblemCase> problemCaseQueryWrapper = new QueryWrapper<>();
        problemCaseQueryWrapper.eq("pid", pid).eq("status", 0);
        if (isUpload) {
            problemCaseQueryWrapper.last("order by length(input) asc,input asc");
        }
        return problemCaseEntityService.list(problemCaseQueryWrapper);
    }

    @Override
    public CommonResult compileSpj(CompileDTO compileDTO) {
        compileDTO.setToken(judgeToken);
        return dispatcher.dispatcher(CallJudgerType.COMPILE, "/compile-spj", compileDTO);
    }

    @Override
    public CommonResult compileInteractive(CompileDTO compileDTO) {
        compileDTO.setToken(judgeToken);
        return dispatcher.dispatcher(CallJudgerType.COMPILE, "/compile-interactive", compileDTO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importRemoteOjProblem(String name, String problemId) {
        QueryWrapper<Problem> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("problem_id", name.toUpperCase() + "-" + problemId);
        Problem problem = problemEntityService.getOne(queryWrapper);
        if (problem != null) {
            throw new StatusFailException("该题目已添加，请勿重复添加！");
        }

        try {
            AbstractProblemCrawler.RemoteProblemInfo otherOjProblemInfo = remoteProblemService
                    .getOtherOJProblemInfo(name.toUpperCase(), problemId);
            if (otherOjProblemInfo != null) {
                Problem importProblem = remoteProblemService.adminAddOtherOJProblem(otherOjProblemInfo, name);
                if (importProblem == null) {
                    throw new StatusFailException("导入新题目失败！请重新尝试！");
                }
            } else {
                throw new StatusFailException("导入新题目失败！原因：可能是与该OJ链接超时或题号格式错误！");
            }
        } catch (Exception e) {
            log.error("导入远程题目异常-------------->", e);
            throw new StatusFailException(e.getMessage());
        }
    }

    @Override
    public void changeProblemAuth(Problem problem) {
        // 普通管理员只能将题目变成隐藏题目和比赛题目
        boolean root = UserSessionUtil.isRoot();

        boolean problemAdmin = UserSessionUtil.isProblemAdmin();

        if (!problemAdmin && !root && problem.getAuth().equals(ProblemEnum.AUTH_PUBLIC.getCode())) {
            throw new StatusForbiddenException("修改失败！你无权限公开题目！");
        }

        UserRolesVO userRolesVO = UserSessionUtil.getUserInfo();

        UpdateWrapper<Problem> problemUpdateWrapper = new UpdateWrapper<>();
        problemUpdateWrapper.eq("id", problem.getId()).set("auth", problem.getAuth()).set("modified_user",
                userRolesVO.getUsername());

        boolean isOk = problemEntityService.update(problemUpdateWrapper);
        if (!isOk) {
            throw new StatusFailException("修改失败");
        }
    }

}