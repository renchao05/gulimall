package com.renchao.gulimall.ware.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renchao.common.constant.WareConstant;
import com.renchao.gulimall.ware.entity.PurchaseDetailEntity;
import com.renchao.gulimall.ware.service.PurchaseDetailService;
import com.renchao.gulimall.ware.service.WareSkuService;
import com.renchao.gulimall.ware.vo.MergeVo;
import com.renchao.gulimall.ware.vo.PurchaseDoneVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renchao.common.utils.PageUtils;
import com.renchao.common.utils.Query;

import com.renchao.gulimall.ware.dao.PurchaseDao;
import com.renchao.gulimall.ware.entity.PurchaseEntity;
import com.renchao.gulimall.ware.service.PurchaseService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;


@Service("purchaseService")
public class PurchaseServiceImpl extends ServiceImpl<PurchaseDao, PurchaseEntity> implements PurchaseService {

    @Autowired
    private PurchaseDetailService detailService;

    @Autowired
    private WareSkuService wareSkuService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(params),
                new QueryWrapper<PurchaseEntity>()
        );
        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageUnreceive(Map<String, Object> params) {
        LambdaQueryWrapper<PurchaseEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseEntity::getStatus, 0).or().eq(PurchaseEntity::getStatus, 1);
        IPage<PurchaseEntity> page = this.page(new Query<PurchaseEntity>().getPage(params), wrapper);
        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void merge(MergeVo merge) {
        List<PurchaseDetailEntity> detailEntities = merge.getItems().stream().map(id -> detailService.getById(id))
                // ??????????????????????????????????????????
                .filter(pd -> pd.getStatus() <= WareConstant.PurchaseDetailStatus.ASSIGNED.getCode())
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(detailEntities)) {
            return;
        }
        Long purchaseId = merge.getPurchaseId();

        // ????????????????????????????????????????????????
        if (purchaseId == null) {
            PurchaseEntity purchase = new PurchaseEntity();
            purchase.setCreateTime(new Date());
            purchase.setUpdateTime(new Date());
            purchase.setStatus(WareConstant.PurchaseStatus.CREATED.getCode());
            this.save(purchase);
            purchaseId = purchase.getId();
        }

        // ???????????????????????????????????????????????????
        PurchaseEntity purchase = this.getById(purchaseId);
        Integer status = purchase.getStatus();
        if (status > WareConstant.PurchaseStatus.ASSIGNED.getCode()) {
            return;
        }

        // ??????????????????????????????
        Long finalPurchaseId = purchaseId;
        detailEntities.forEach(d -> {
            PurchaseDetailEntity detail = new PurchaseDetailEntity();
            detail.setId(d.getId());
            detail.setPurchaseId(finalPurchaseId);
            detail.setStatus(WareConstant.PurchaseDetailStatus.ASSIGNED.getCode());
            detailService.updateById(detail);
        });
    }

    @Transactional
    @Override
    public void updateByReceived(List<Long> pIds) {
        if (CollectionUtils.isEmpty(pIds)) {
            return;
        }
        pIds.forEach(pId->{
            // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            PurchaseEntity p = this.getById(pId);
            Integer status = p.getStatus();
            if (status != WareConstant.PurchaseStatus.ASSIGNED.getCode()) {
                return;
            }

            // ?????????????????????
            PurchaseEntity purchase = new PurchaseEntity();
            purchase.setId(pId);
            purchase.setStatus(WareConstant.PurchaseStatus.RECEIVED.getCode());
            purchase.setUpdateTime(new Date());
            this.updateById(purchase);

            // ????????????????????????
            LambdaQueryWrapper<PurchaseDetailEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PurchaseDetailEntity::getPurchaseId, pId);
            detailService.list(wrapper).forEach(d->{
                PurchaseDetailEntity detail = new PurchaseDetailEntity();
                detail.setId(d.getId());
                detail.setStatus(WareConstant.PurchaseDetailStatus.PURCHASING.getCode());
                detailService.updateById(detail);
            });
        });
    }

    @Transactional
    @Override
    public void updateByDone(PurchaseDoneVo doneVo) {

        // 1??????????????????????????????
        AtomicBoolean completed = new AtomicBoolean(true);
        doneVo.getItems().forEach(i->{
            Long id = i.getItemId();
            Integer status = i.getStatus();
            PurchaseDetailEntity item = detailService.getById(id);
            // ?????????????????????????????????????????????????????????????????????????????????????????????
            if (item.getStatus() != WareConstant.PurchaseDetailStatus.PURCHASING.getCode()) {
                return;
            }
            PurchaseDetailEntity detailEntity = new PurchaseDetailEntity();
            detailEntity.setId(id);
            if (status == WareConstant.PurchaseDetailStatus.FAILED.getCode()) {
                detailEntity.setStatus(WareConstant.PurchaseDetailStatus.FAILED.getCode());
                completed.set(false);
            } else if (status == WareConstant.PurchaseDetailStatus.COMPLETED.getCode()) {
                detailEntity.setStatus(WareConstant.PurchaseDetailStatus.COMPLETED.getCode());
                // 3???????????????
                wareSkuService.addStock(item.getSkuId(), item.getWareId(), item.getSkuNum());
            }
            detailService.updateById(detailEntity);
        });
        // 2????????????????????????
        PurchaseEntity purchaseEntity = new PurchaseEntity();
        purchaseEntity.setId(doneVo.getId());
        if (completed.get()) {
            purchaseEntity.setStatus(WareConstant.PurchaseStatus.COMPLETED.getCode());
        } else {
            purchaseEntity.setStatus(WareConstant.PurchaseStatus.HAS_ERROR.getCode());
        }
        this.updateById(purchaseEntity);


    }


}