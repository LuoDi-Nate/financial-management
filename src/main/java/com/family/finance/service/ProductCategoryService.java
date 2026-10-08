package com.family.finance.service;

import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.repository.ProductCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 产品类目服务 · v0.2 (FR-40d)
 *
 * - listAll · 全部类目(按 display_order)
 * - findByCode · 按 code 查
 * - findApplicableFor(type) · 列出适用本 account.type 的类目
 *   - 用于 /accounts/{id}/edit + /accounts/new 类目下拉
 *
 * 详见 TDD § 决策 4 / 决策 9
 */
@Service
@RequiredArgsConstructor
public class ProductCategoryService {

    private final ProductCategoryMapper mapper;

    public List<ProductCategory> listAll() {
        return mapper.findAll();
    }

    public Optional<ProductCategory> findByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return mapper.findByCode(code);
    }

    /** 列出适用于指定 AccountType 的类目(applicable_types 含本 type 或 *) */
    public List<ProductCategory> findApplicableFor(AccountType type) {
        return mapper.findAll().stream()
                .filter(c -> c.appliesTo(type))
                .toList();
    }

    /** 根据 type 给出 default category code(用于新建账户时预填,与 V11 回填逻辑一致) */
    public String defaultCodeFor(AccountType type) {
        if (type == null) return "OTHER";
        return switch (type) {
            case CASH     -> "CASH_DEPOSIT";
            case STOCK    -> "A_STOCK";
            case WEALTH   -> "BANK_WEALTH";
            case FUND     -> "MIXED_FUND";   // v1.30 · 基金账户里最常见的是偏股 / 混合;用户可改
            case CRYPTO   -> "CRYPTO";
            case METAL    -> "PRECIOUS_METAL";
            case LOAN     -> "LIABILITY";
            case PROPERTY -> "PROPERTY_RES";
            case INSURANCE -> "SAVINGS_INSURANCE";
            case OTHER    -> "OTHER";
        };
    }
}
