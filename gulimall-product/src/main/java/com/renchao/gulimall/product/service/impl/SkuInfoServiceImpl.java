package com.renchao.gulimall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renchao.common.to.CartItemTo;
import com.renchao.common.utils.R;
import com.renchao.gulimall.product.entity.*;
import com.renchao.gulimall.product.feign.SeckillFeignService;
import com.renchao.gulimall.product.feign.WareFeignService;
import com.renchao.gulimall.product.service.*;
import com.renchao.gulimall.product.vo.*;
import com.renchao.gulimall.product.vo.spuinfo.Attr;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renchao.common.utils.PageUtils;
import com.renchao.common.utils.Query;

import com.renchao.gulimall.product.dao.SkuInfoDao;
import org.springframework.util.StringUtils;


@Service("skuInfoService")
public class SkuInfoServiceImpl extends ServiceImpl<SkuInfoDao, SkuInfoEntity> implements SkuInfoService {

    @Autowired
    private WareFeignService wareFeignService;

    @Autowired
    SeckillFeignService seckillFeignService;

    @Autowired
    private SkuImagesService skuImagesService;

    @Autowired
    private SkuSaleAttrValueService saleAttrValueService;

    @Autowired
    private ProductAttrValueService attrValueService;

    @Autowired
    private SpuInfoDescService spuInfoDescService;

    @Autowired
    private AttrGroupService attrGroupService;

    @Autowired
    private AttrAttrgroupRelationService attrRelationService;

    @Autowired
    private ThreadPoolExecutor executor;


    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SkuInfoEntity> page = this.page(
                new Query<SkuInfoEntity>().getPage(params),
                new QueryWrapper<SkuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageWrapper(Map<String, Object> params) {
        LambdaQueryWrapper<SkuInfoEntity> wrapper = new LambdaQueryWrapper<>();
        Object key = params.get("key");
        Object catelogId = params.get("catelogId");
        Object brandId = params.get("brandId");
        try {
            BigDecimal min = new BigDecimal((String) params.get("min"));
            wrapper.ge(SkuInfoEntity::getPrice, min);
        } catch (Exception ignored) {
        }
        try {
            BigDecimal max = new BigDecimal((String) params.get("max"));
            wrapper.le(max.compareTo(new BigDecimal(0)) > 0, SkuInfoEntity::getPrice, max);
        } catch (Exception ignored) {
        }

        if (!StringUtils.isEmpty(key)) {
            wrapper.and(w -> w.eq(SkuInfoEntity::getSkuId, key).or().like(SkuInfoEntity::getSkuName, key));
        }
        wrapper.eq(!StringUtils.isEmpty(catelogId) && !catelogId.equals("0"), SkuInfoEntity::getCatalogId, catelogId);
        wrapper.eq(!StringUtils.isEmpty(brandId) && !brandId.equals("0"), SkuInfoEntity::getBrandId, brandId);
        IPage<SkuInfoEntity> page = this.page(new Query<SkuInfoEntity>().getPage(params), wrapper);
        return new PageUtils(page);
    }

    @Override
    public List<SkuInfoEntity> listBySpuId(Long spuId) {
        LambdaQueryWrapper<SkuInfoEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkuInfoEntity::getSpuId, spuId);
        return this.list(wrapper);
    }

    /**
     * ???????????????
     */
    @Override
    public SkuItemVo item(Long skuId) throws ExecutionException, InterruptedException {
        SkuItemVo skuItemVo = new SkuItemVo();
        // sku????????????
        CompletableFuture<SkuInfoEntity> skuInfoFuture = CompletableFuture.supplyAsync(() -> {
            SkuInfoEntity skuInfo = this.getById(skuId);
            skuItemVo.setInfo(skuInfo);
            return skuInfo;
        }, executor);

        // ???????????????
        CompletableFuture<Void> stockFuture = CompletableFuture.runAsync(() -> {
            Boolean hasStock = hasStock(skuId);
            skuItemVo.setHasStock(hasStock);
        }, executor);

        // sku???????????????
        CompletableFuture<Void> imagesFuture = CompletableFuture.runAsync(() -> {
            List<SkuImagesEntity> images = skuImagesService.listBySkuId(skuId);
            skuItemVo.setImages(images);
        }, executor);

        // ??????spu???????????????
        CompletableFuture<Void> saleAttrFuture = skuInfoFuture.thenAcceptAsync(res -> {
            List<SkuItemSaleAttrVo> saleAttrValue = getSpuSaleAttrValue(res.getSpuId(), skuId);
            skuItemVo.setSaleAttr(saleAttrValue);
        }, executor);

        // ??????spu???????????????
        CompletableFuture<Void> despFuture = skuInfoFuture.thenAcceptAsync(res -> {
            SpuInfoDescEntity desc = spuInfoDescService.getById(res.getSpuId());
            skuItemVo.setDesp(desc);
        }, executor);

        // ??????spu?????????????????????
        CompletableFuture<Void> groupAttrFuture = skuInfoFuture.thenAcceptAsync(res -> {
            List<SpuItemAttrGroupVo> groupAttrs = getGroupAttrs(res.getSpuId(), res.getCatalogId());
            skuItemVo.setGroupAttrs(groupAttrs);
        }, executor);

        // ??????????????????????????????sku???????????????????????????????????????????????????????????????????????????????????????????????????????????????
        // ????????????????????????
//        CompletableFuture<Void> seckillFuture = CompletableFuture.runAsync(() -> {
//            try {
//                R r = seckillFeignService.getSeckillSkuInfo(skuId.toString());
//                SeckillInfo seckillInfo = r.getData("data", SeckillInfo.class);
//                skuItemVo.setSeckillInfo(seckillInfo);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }, executor);


        // ???????????????????????????
        CompletableFuture.allOf(stockFuture, imagesFuture, saleAttrFuture, despFuture, groupAttrFuture).get();

        return skuItemVo;
    }

    @Override
    public CartItemTo getCarItem(Long skuId) {
        // ??????????????????
        CartItemTo cartItemTo = new CartItemTo();
        SkuInfoEntity entity = this.getById(skuId);
        cartItemTo.setTitle(entity.getSkuTitle());
        cartItemTo.setImage(entity.getSkuDefaultImg());
        cartItemTo.setPrice(entity.getPrice());

        // ??????????????????
        List<SkuSaleAttrValueEntity> entities = saleAttrValueService.listBySkuIds(Collections.singletonList(skuId));
        List<String> collect = entities.stream().map(e -> e.getAttrName() + ":" + e.getAttrValue()).collect(Collectors.toList());
        cartItemTo.setSkuAttr(collect);
        return cartItemTo;
    }

    /**
     * ??????spu?????????????????????
     *
     * @param spuId
     * @param catalogId
     * @return
     */
    private List<SpuItemAttrGroupVo> getGroupAttrs(Long spuId, Long catalogId) {
        Map<Long, ProductAttrValueEntity> attrMap = new HashMap<>();
        attrValueService.getBySpuId(spuId).forEach(a -> attrMap.put(a.getAttrId(), a));

        List<SpuItemAttrGroupVo> groupAttrs = new ArrayList<>();
        // ??????????????????
        List<AttrGroupEntity> attrGroups = attrGroupService.listByCatId(catalogId);
        attrGroups.forEach(attrGroup -> {
            SpuItemAttrGroupVo spuItemAttrGroupVo = new SpuItemAttrGroupVo();
            spuItemAttrGroupVo.setGroupName(attrGroup.getAttrGroupName());
            List<Attr> attrs = new ArrayList<>();
            List<Long> attrIds = attrRelationService.attrIdListByGroupId(attrGroup.getAttrGroupId());
            attrIds.forEach(id -> {
                ProductAttrValueEntity attrValue = attrMap.get(id);
                if (attrValue != null) {
                    Attr attr = new Attr();
                    attr.setAttrId(id);
                    attr.setAttrName(attrValue.getAttrName());
                    attr.setAttrValue(attrValue.getAttrValue());
                    attrs.add(attr);
                }
            });
            spuItemAttrGroupVo.setAttrs(attrs);
            groupAttrs.add(spuItemAttrGroupVo);
        });
        return groupAttrs;
    }

    /**
     * ?????????????????????
     *
     * @param skuId
     * @return
     */
    private Boolean hasStock(Long skuId) {
        try {
            R r = wareFeignService.getStock(skuId);
            Integer stock = (Integer) r.get("stock");
            if (stock == null || stock == 0) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * ??????spu???????????????
     */
    private List<SkuItemSaleAttrVo> getSpuSaleAttrValue(Long spuId, Long skuId) {
        List<Long> skuIds = this.listBySpuId(spuId).stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());
        List<SkuSaleAttrValueEntity> SaleAttrs = saleAttrValueService.listBySkuIds(skuIds);

        // ?????????????????????,????????????????????????
        Map<String, List<SkuSaleAttrValueEntity>> map = SaleAttrs.stream().collect(Collectors.groupingBy(SkuSaleAttrValueEntity::getAttrName));
        // ?????????????????????????????????????????????????????????6G,8G
        Map<String, List<SkuSaleAttrValueEntity>> valueMap = SaleAttrs.stream().collect(Collectors.groupingBy(SkuSaleAttrValueEntity::getAttrValue));
        // ????????????sku??????
        List<SkuSaleAttrValueEntity> nowAttrs = SaleAttrs.stream().filter(saleAttr -> Objects.equals(saleAttr.getSkuId(), skuId)).collect(Collectors.toList());

        List<SkuItemSaleAttrVo> saleAttr = new ArrayList<>();
        // ????????????
        Set<String> attrKs = map.keySet();
        for (String attrName : attrKs) {
            SkuItemSaleAttrVo itemSaleAttrVo = new SkuItemSaleAttrVo();
            itemSaleAttrVo.setAttrName(attrName);
            List<AttrValueWithSkuIdVo> attrValues = new ArrayList<>();
            // ??????????????????????????????skuId?????????????????????????????????skuId
            map.get(attrName).stream()
                    .collect(Collectors.groupingBy(SkuSaleAttrValueEntity::getAttrValue))
                    .forEach((vk, vv) -> {
//                System.out.println("   " + vk);
                        AttrValueWithSkuIdVo withSkuIdVo = new AttrValueWithSkuIdVo();
                        withSkuIdVo.setAttrValue(vk);
                        List<Long> ids = vv.stream().map(SkuSaleAttrValueEntity::getSkuId).collect(Collectors.toList());
                        for (SkuSaleAttrValueEntity nAttr : nowAttrs) {
                            if (!nAttr.getAttrName().equals(attrName)) {
                                // ????????????????????????
                                List<Long> skuList = valueMap.get(nAttr.getAttrValue()).stream().map(SkuSaleAttrValueEntity::getSkuId).collect(Collectors.toList());
                                ids.retainAll(skuList);
                            }
                        }
//                System.out.println(ids);
                        withSkuIdVo.setSkuId(ids.get(0));
                        attrValues.add(withSkuIdVo);
                    });
            saleAttr.add(itemSaleAttrVo);
            itemSaleAttrVo.setAttrValues(attrValues);
        }
        return saleAttr;
    }

}