package com.renchao.gulimall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.renchao.gulimall.product.entity.AttrAttrgroupRelationEntity;
import com.renchao.gulimall.product.entity.AttrGroupEntity;
import com.renchao.gulimall.product.entity.CategoryEntity;
import com.renchao.gulimall.product.service.AttrAttrgroupRelationService;
import com.renchao.gulimall.product.service.AttrGroupService;
import com.renchao.gulimall.product.service.CategoryService;
import com.renchao.gulimall.product.vo.AttrPathVo;
import com.renchao.gulimall.product.vo.AttrRespVo;
import com.renchao.gulimall.product.vo.AttrVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renchao.common.utils.PageUtils;
import com.renchao.common.utils.Query;

import com.renchao.gulimall.product.dao.AttrDao;
import com.renchao.gulimall.product.entity.AttrEntity;
import com.renchao.gulimall.product.service.AttrService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Transactional
@Service("attrService")
public class AttrServiceImpl extends ServiceImpl<AttrDao, AttrEntity> implements AttrService {

    @Autowired
    private AttrAttrgroupRelationService relationService;

    @Autowired
    private AttrGroupService groupService;

    @Autowired
    private CategoryService categoryService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<AttrEntity> page = this.page(
                new Query<AttrEntity>().getPage(params),
                new QueryWrapper<AttrEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public void saveAttr(AttrVo attr) {
        // ????????????
        AttrEntity entity = new AttrEntity();
        BeanUtils.copyProperties(attr, entity);
        this.save(entity);
        // ????????????????????????????????????
        if (attr.getAttrType() == 1) {
            AttrAttrgroupRelationEntity relation = new AttrAttrgroupRelationEntity();
            relation.setAttrId(entity.getAttrId());
            relation.setAttrGroupId(attr.getAttrGroupId());
            relationService.save(relation);
        }
    }

    @Override
    public PageUtils queryPage(Map<String, Object> params, String attrType, Long catelogId) {
        Object key = params.get("key");
        // ??????????????????????????????
        int type = attrType.equals("base") ? 1 : 0;
        LambdaQueryWrapper<AttrEntity> wrapper = new LambdaQueryWrapper<>();
        // ????????????????????????
        wrapper.eq(AttrEntity::getAttrType, type);
        if (!StringUtils.isEmpty(key)) {
            wrapper.and(qw -> qw.eq(AttrEntity::getAttrId, key).or().like(AttrEntity::getAttrName, key));
        }
        wrapper.eq(catelogId != 0, AttrEntity::getCatelogId, catelogId);
        IPage<AttrEntity> page = this.page(new Query<AttrEntity>().getPage(params), wrapper);
        PageUtils pageUtils = new PageUtils(page);

        // ?????????????????????
        List<AttrEntity> records = page.getRecords();
        List<AttrRespVo> list = records.stream().map(attrEntity -> {
            AttrRespVo respVo = new AttrRespVo();
            BeanUtils.copyProperties(attrEntity, respVo);
            if (type == 1) {    // ???????????????????????????????????????
                AttrAttrgroupRelationEntity relation = relationService.queryByAttrId(respVo.getAttrId());
                if (relation != null) {
                    AttrGroupEntity attrGroup = groupService.getById(relation.getAttrGroupId());
                    if (attrGroup != null) {
                        respVo.setGroupName(attrGroup.getAttrGroupName());
                    }
                }
            }
            CategoryEntity category = categoryService.getById(respVo.getCatelogId());
            if (category != null) {
                respVo.setCatelogName(category.getName());
            }
            return respVo;
        }).collect(Collectors.toList());
        pageUtils.setList(list);
        return pageUtils;
    }

    @Override
    public AttrPathVo getAttr(Long attrId) {
        AttrEntity entity = this.getById(attrId);
        AttrPathVo pathVo = new AttrPathVo();
        BeanUtils.copyProperties(entity, pathVo);
        Long catelogId = entity.getCatelogId();
        if (catelogId != null) {
            pathVo.setCatelogPath(categoryService.findCatelogPath(catelogId));
        }
        // ?????????????????????????????????ID
        if (entity.getAttrType() == 1) {
            AttrAttrgroupRelationEntity relation = relationService.queryByAttrId(pathVo.getAttrId());
            if (relation != null) {
                pathVo.setAttrGroupId(relation.getAttrGroupId());
            }
        }
        return pathVo;
    }

    @Override
    public void updateDetails(AttrRespVo attr) {
        AttrEntity entity = new AttrEntity();
        BeanUtils.copyProperties(attr, entity);
        this.updateById(entity);    // ????????????

        // ????????????????????????????????????????????????????????????
        if (attr.getAttrType() == 0) {
            return;
        }
        //  ????????????
        AttrAttrgroupRelationEntity relation = new AttrAttrgroupRelationEntity();
        BeanUtils.copyProperties(attr, relation);

        // ????????????????????????????????????????????????????????????
        int count = relationService.count(new LambdaQueryWrapper<AttrAttrgroupRelationEntity>()
                .eq(AttrAttrgroupRelationEntity::getAttrId, attr.getAttrId()));
        if (count > 0) {
            LambdaUpdateWrapper<AttrAttrgroupRelationEntity> wrapper =
                    new LambdaUpdateWrapper<AttrAttrgroupRelationEntity>()
                            .eq(AttrAttrgroupRelationEntity::getAttrId, attr.getAttrId());
            relationService.update(relation, wrapper);
        } else {
            relationService.save(relation);
        }
    }

}