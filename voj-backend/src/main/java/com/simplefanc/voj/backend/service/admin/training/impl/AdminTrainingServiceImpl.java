package com.simplefanc.voj.backend.service.admin.training.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simplefanc.voj.backend.common.constants.TrainingEnum;
import com.simplefanc.voj.backend.common.exception.StatusFailException;
import com.simplefanc.voj.backend.common.exception.StatusForbiddenException;
import com.simplefanc.voj.backend.dao.training.MappingTrainingCategoryEntityService;
import com.simplefanc.voj.backend.dao.training.TrainingCategoryEntityService;
import com.simplefanc.voj.backend.dao.training.TrainingEntityService;
import com.simplefanc.voj.backend.dao.training.TrainingRegisterEntityService;
import com.simplefanc.voj.backend.pojo.dto.TrainingDTO;
import com.simplefanc.voj.backend.pojo.vo.UserRolesVO;
import com.simplefanc.voj.backend.service.admin.training.AdminTrainingRecordService;
import com.simplefanc.voj.backend.service.admin.training.AdminTrainingService;
import com.simplefanc.voj.backend.shiro.UserSessionUtil;
import com.simplefanc.voj.common.pojo.entity.training.MappingTrainingCategory;
import com.simplefanc.voj.common.pojo.entity.training.Training;
import com.simplefanc.voj.common.pojo.entity.training.TrainingCategory;
import com.simplefanc.voj.common.pojo.entity.training.TrainingRegister;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * @Author: chenfan
 * @Date: 2022/3/9 19:46
 * @Description:
 */

@Service
@RequiredArgsConstructor
public class AdminTrainingServiceImpl implements AdminTrainingService {

    private final TrainingEntityService trainingEntityService;

    private final MappingTrainingCategoryEntityService mappingTrainingCategoryEntityService;

    private final TrainingCategoryEntityService trainingCategoryEntityService;

    private final TrainingRegisterEntityService trainingRegisterEntityService;

    private final AdminTrainingRecordService adminTrainingRecordService;

    @Override
    public IPage<Training> getTrainingList(Integer limit, Integer currentPage, String keyword) {

        if (currentPage == null || currentPage < 1) {
            currentPage = 1;
        }
        if (limit == null || limit < 1) {
            limit = 10;
        }
        IPage<Training> iPage = new Page<>(currentPage, limit);
        QueryWrapper<Training> queryWrapper = new QueryWrapper<>();
        // 过滤密码
        queryWrapper.select(Training.class, info -> !"private_pwd".equals(info.getColumn()));
        if (StrUtil.isNotEmpty(keyword)) {
            keyword = keyword.trim();
            queryWrapper.like("title", keyword).or().like("id", keyword).or().like("`rank`", keyword);
        }
        queryWrapper.orderByAsc("`rank`");

        return trainingEntityService.page(iPage, queryWrapper);
    }

    @Override
    public TrainingDTO getTraining(Long tid) {
        // 获取本场训练的信息
        Training training = trainingEntityService.getById(tid);
        // 查询不存在
        if (training == null) {
            throw new StatusFailException("查询失败：该训练不存在,请检查参数tid是否准确！");
        }

        // 获取当前登录的用户
        UserRolesVO userRolesVO = UserSessionUtil.getUserInfo();
        // 是否为超级管理员
        boolean isRoot = UserSessionUtil.isRoot();
        // 只有超级管理员和训练拥有者才能操作
        if (!isRoot && !userRolesVO.getUsername().equals(training.getAuthor())) {
            throw new StatusForbiddenException("对不起，你无权限操作！");
        }

        TrainingDTO trainingDTO = new TrainingDTO();
        trainingDTO.setTraining(training);

        QueryWrapper<MappingTrainingCategory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tid", tid);
        MappingTrainingCategory mappingTrainingCategory = mappingTrainingCategoryEntityService.getOne(queryWrapper,
                false);
        TrainingCategory trainingCategory = null;
        if (mappingTrainingCategory != null) {
            trainingCategory = trainingCategoryEntityService.getById(mappingTrainingCategory.getCid());
        }
        trainingDTO.setTrainingCategory(trainingCategory);
        return trainingDTO;
    }

    @Override
    public void deleteTraining(Long tid) {
        boolean isOk = trainingEntityService.removeById(tid);
        // Training的id为其他表的外键的表中的对应数据都会被一起删除！
        if (!isOk) {
            throw new StatusFailException("删除失败！");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addTraining(TrainingDTO trainingDTO) {

        Training training = trainingDTO.getTraining();
        trainingEntityService.save(training);
        TrainingCategory trainingCategory = trainingDTO.getTrainingCategory();
        if (trainingCategory.getId() == null) {
            try {
                trainingCategoryEntityService.save(trainingCategory);
            } catch (Exception ignored) {
                QueryWrapper<TrainingCategory> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("name", trainingCategory.getName());
                trainingCategory = trainingCategoryEntityService.getOne(queryWrapper, false);
            }
        }

        boolean isOk = mappingTrainingCategoryEntityService
                .save(new MappingTrainingCategory().setTid(training.getId()).setCid(trainingCategory.getId()));
        if (!isOk) {
            throw new StatusFailException("添加失败！");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTraining(TrainingDTO trainingDTO) {
        // 获取当前登录的用户
        UserRolesVO userRolesVO = UserSessionUtil.getUserInfo();
        // 是否为超级管理员
        boolean isRoot = UserSessionUtil.isRoot();
        // 只有超级管理员和训练拥有者才能操作
        if (!isRoot && !userRolesVO.getUsername().equals(trainingDTO.getTraining().getAuthor())) {
            throw new StatusForbiddenException("对不起，你无权限操作！");
        }
        Training training = trainingDTO.getTraining();
        Training oldTraining = trainingEntityService.getById(training.getId());
        trainingEntityService.updateById(training);

        // 私有训练 修改密码 需要清空之前注册训练的记录
        if (training.getAuth().equals(TrainingEnum.AUTH_PRIVATE.getValue())) {
            if (!Objects.equals(training.getPrivatePwd(), oldTraining.getPrivatePwd())) {
                UpdateWrapper<TrainingRegister> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("tid", training.getId());
                trainingRegisterEntityService.remove(updateWrapper);
            }
        }

        TrainingCategory trainingCategory = trainingDTO.getTrainingCategory();
        if (trainingCategory.getId() == null) {
            try {
                trainingCategoryEntityService.save(trainingCategory);
            } catch (Exception ignored) {
                QueryWrapper<TrainingCategory> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("name", trainingCategory.getName());
                trainingCategory = trainingCategoryEntityService.getOne(queryWrapper, false);
            }
        }

        MappingTrainingCategory mappingTrainingCategory = mappingTrainingCategoryEntityService
                .getOne(new QueryWrapper<MappingTrainingCategory>().eq("tid", training.getId()), false);

        if (mappingTrainingCategory == null) {
            mappingTrainingCategoryEntityService
                    .save(new MappingTrainingCategory().setTid(training.getId()).setCid(trainingCategory.getId()));
            adminTrainingRecordService.checkSyncRecord(trainingDTO.getTraining());
        } else {
            if (!mappingTrainingCategory.getCid().equals(trainingCategory.getId())) {
                UpdateWrapper<MappingTrainingCategory> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("tid", training.getId()).set("cid", trainingCategory.getId());
                boolean isOk = mappingTrainingCategoryEntityService.update(null, updateWrapper);
                if (isOk) {
                    adminTrainingRecordService.checkSyncRecord(trainingDTO.getTraining());
                } else {
                    throw new StatusFailException("修改失败");
                }
            }
        }

    }

    @Override
    public void changeTrainingStatus(Long tid, String author, Boolean status) {
        // 获取当前登录的用户
        UserRolesVO userRolesVO = UserSessionUtil.getUserInfo();
        // 是否为超级管理员
        boolean isRoot = UserSessionUtil.isRoot();
        // 只有超级管理员和训练拥有者才能操作
        if (!isRoot && !userRolesVO.getUsername().equals(author)) {
            throw new StatusForbiddenException("对不起，你无权限操作！");
        }

        boolean isOk = trainingEntityService.saveOrUpdate(new Training().setId(tid).setStatus(status));
        if (!isOk) {
            throw new StatusFailException("修改失败");
        }
    }

}