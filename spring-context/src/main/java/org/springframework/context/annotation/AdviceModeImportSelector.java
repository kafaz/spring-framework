/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;

import org.jspecify.annotations.Nullable;

import org.springframework.core.GenericTypeResolver;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.Assert;

/**
 * 基于{@link AdviceMode}值选择导入配置的{@link ImportSelector}实现基类。
 * 专为处理类似{@code @Enable*}注解（如{@code @EnableTransactionManagement}）的{@code mode}属性设计。
 *
 * @param <A> 包含{@link AdviceMode}属性的注解类型（如{@code EnableTransactionManagement}）
 */
public abstract class AdviceModeImportSelector<A extends Annotation> implements ImportSelector {

    /**
     * 默认的{@link AdviceMode}属性名称，值为"mode"。
     */
    public static final String DEFAULT_ADVICE_MODE_ATTRIBUTE_NAME = "mode";

    /**
     * 获取注解{@code A}中定义{@link AdviceMode}属性的名称，默认返回"mode"。
     * 子类可覆盖此方法以支持自定义属性名。
     */
    protected String getAdviceModeAttributeName() {
        return DEFAULT_ADVICE_MODE_ATTRIBUTE_NAME;
    }

    /**
     * 核心方法：解析注解并根据{@code AdviceMode}值选择导入的配置类。
     * 执行流程：
     * 1. 通过泛型解析确定注解类型{@code A}（如{@code EnableTransactionManagement}）。
     * 2. 验证导入该选择器的配置类是否包含注解{@code A}，若无则抛出异常。
     * 3. 从注解中提取{@link AdviceMode}属性值（如PROXY或ASPECTJ）。
     * 4. 调用{@link #selectImports(AdviceMode)}由子类决定具体导入的配置类 <button class="citation-flag" data-index="1"><button class="citation-flag" data-index="2"><button class="citation-flag" data-index="6">。
     */
    @Override
    public final String[] selectImports(AnnotationMetadata importingClassMetadata) {
        // 解析泛型参数A的具体类型（如EnableTransactionManagement.class）
        Class<?> annType = GenericTypeResolver.resolveTypeArgument(getClass(), AdviceModeImportSelector.class);
        Assert.state(annType != null, "无法解析AdviceModeImportSelector的泛型参数");

        // 验证配置类是否包含注解A，提取注解属性
        AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(importingClassMetadata, annType);
        if (attributes == null) {
            throw new IllegalArgumentException(String.format(
                    "配置类'%s'未标注@%s注解", 
                    importingClassMetadata.getClassName(), annType.getSimpleName()));
        }

        // 获取注解中定义的AdviceMode值（如PROXY或ASPECTJ）
        AdviceMode adviceMode = attributes.getEnum(getAdviceModeAttributeName());
        
        // 由子类根据AdviceMode选择具体导入的配置类
        String[] imports = selectImports(adviceMode);
        if (imports == null) {
            throw new IllegalArgumentException("无法处理AdviceMode: " + adviceMode);
        }
        return imports;
    }

    /**
     * 抽象方法：根据{@link AdviceMode}值返回需导入的配置类名称。
     * 子类必须实现此方法定义不同模式下的配置类（如PROXY模式导入代理配置，ASPECTJ模式导入切面配置） <button class="citation-flag" data-index="1"><button class="citation-flag" data-index="6"><button class="citation-flag" data-index="10">。
     *
     * @param adviceMode 从注解中解析的{@link AdviceMode}值
     * @return 需导入的配置类全限定名数组，返回{@code null}表示无法处理该模式
     */
    protected abstract String @Nullable [] selectImports(AdviceMode adviceMode);
}

