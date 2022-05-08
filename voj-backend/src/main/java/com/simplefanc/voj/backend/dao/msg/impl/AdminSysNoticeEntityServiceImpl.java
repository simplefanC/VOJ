package com.simplefanc.voj.backend.dao.msg.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simplefanc.voj.backend.dao.msg.AdminSysNoticeEntityService;
import com.simplefanc.voj.backend.mapper.AdminSysNoticeMapper;
import com.simplefanc.voj.backend.pojo.vo.AdminSysNoticeVo;
import com.simplefanc.voj.common.pojo.entity.msg.AdminSysNotice;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @Author: chenfan
 * @Date: 2021/10/1 20:34
 * @Description:
 */
@Service
public class AdminSysNoticeEntityServiceImpl extends ServiceImpl<AdminSysNoticeMapper, AdminSysNotice>
        implements AdminSysNoticeEntityService {

    @Resource
    private AdminSysNoticeMapper adminSysNoticeMapper;

    @Override
    public IPage<AdminSysNoticeVo> getSysNotice(int limit, int currentPage, String type) {
        Page<AdminSysNoticeVo> page = new Page<>(currentPage, limit);
        return adminSysNoticeMapper.getAdminSysNotice(page, type);
    }

}