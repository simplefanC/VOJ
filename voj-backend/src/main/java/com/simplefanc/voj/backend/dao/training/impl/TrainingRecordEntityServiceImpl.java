package com.simplefanc.voj.backend.dao.training.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simplefanc.voj.backend.dao.training.TrainingRecordEntityService;
import com.simplefanc.voj.backend.mapper.TrainingRecordMapper;
import com.simplefanc.voj.backend.pojo.vo.TrainingRecordVo;
import com.simplefanc.voj.common.pojo.entity.training.TrainingRecord;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * @Author: chenfan
 * @Date: 2021/11/21 23:39
 * @Description:
 */
@Service
public class TrainingRecordEntityServiceImpl extends ServiceImpl<TrainingRecordMapper, TrainingRecord>
        implements TrainingRecordEntityService {

    @Resource
    private TrainingRecordMapper trainingRecordMapper;

    @Override
    public List<TrainingRecordVo> getTrainingRecord(Long tid) {
        return trainingRecordMapper.getTrainingRecord(tid);
    }

}