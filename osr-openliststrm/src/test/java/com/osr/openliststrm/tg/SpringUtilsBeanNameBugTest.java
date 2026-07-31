package com.osr.openliststrm.tg;

import com.osr.openliststrm.service.ICopyService;
import com.osr.openliststrm.service.IStrmService;
import com.osr.openliststrm.service.impl.CopyServiceImpl;
import com.osr.openliststrm.service.impl.StrmServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 复现 StrmBot/ResponseHandler 中 SpringUtils.getBean("strmService")/getBean("copyService")
 * 因 Bean 名不匹配而失败的问题。
 *
 * StrmServiceImpl/CopyServiceImpl 只标注了裸 @Service（无显式名字），Spring 默认按
 * "类名首字母小写" 生成 Bean 名，即 "strmServiceImpl"/"copyServiceImpl"，
 * 而非代码中按字符串查找的 "strmService"/"copyService"。
 */
class SpringUtilsBeanNameBugTest {

    @Test
    void springDefaultBeanNameGenerator_对StrmServiceImpl生成的名字不是strmService() {
        // 使用 Spring 真实的默认 Bean 命名生成器，作用于本仓库中真实的 StrmServiceImpl 类
        AnnotationBeanNameGenerator generator = new AnnotationBeanNameGenerator();
        AnnotatedBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(StrmServiceImpl.class);
        String generatedName = generator.generateBeanName(beanDefinition, new DefaultListableBeanFactory());

        assertEquals("strmServiceImpl", generatedName, "Spring实际会注册的Bean名");
        assertNotEquals("strmService", generatedName, "而代码里getBean(\"strmService\")查找的是这个名字");
    }

    @Test
    void springDefaultBeanNameGenerator_对CopyServiceImpl生成的名字不是copyService() {
        AnnotationBeanNameGenerator generator = new AnnotationBeanNameGenerator();
        AnnotatedBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(CopyServiceImpl.class);
        String generatedName = generator.generateBeanName(beanDefinition, new DefaultListableBeanFactory());

        assertEquals("copyServiceImpl", generatedName);
        assertNotEquals("copyService", generatedName);
    }

    /**
     * 端到端复现：用真实的 BeanFactory 按生产环境同样的方式（裸 @Service 注解、
     * AnnotationBeanNameGenerator 生成名字）注册 StrmServiceImpl/CopyServiceImpl 的 Bean 定义
     * （不触发依赖注入实例化，只验证名字解析），确认 getBean("strmService") 确实会抛出
     * NoSuchBeanDefinitionException —— 这正是 SpringUtils.getBean(String) 内部调用的方法，
     * 也就是 /strmdir、/syncdir 触发 forceReply 回复时会实际发生的异常。
     */
    @Test
    void 真实BeanFactory中按字符串getBean_strmService_抛出NoSuchBeanDefinitionException() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AnnotationBeanNameGenerator generator = new AnnotationBeanNameGenerator();

        AnnotatedGenericBeanDefinition strmDef = new AnnotatedGenericBeanDefinition(StrmServiceImpl.class);
        beanFactory.registerBeanDefinition(generator.generateBeanName(strmDef, beanFactory), strmDef);
        AnnotatedGenericBeanDefinition copyDef = new AnnotatedGenericBeanDefinition(CopyServiceImpl.class);
        beanFactory.registerBeanDefinition(generator.generateBeanName(copyDef, beanFactory), copyDef);

        // 按 SpringUtils.getBean(String) 同样的方式按名查找 —— 复现线上会抛出的异常
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> beanFactory.getBean("strmService"));
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> beanFactory.getBean("copyService"));

        // 而按类型查找（SpringUtils.getBean(Class) 的实现方式）不依赖猜测名字，能正确定位到同一个Bean定义
        assertEquals("strmServiceImpl", beanFactory.getBeanNamesForType(IStrmService.class)[0]);
        assertEquals("copyServiceImpl", beanFactory.getBeanNamesForType(ICopyService.class)[0]);
    }
}
