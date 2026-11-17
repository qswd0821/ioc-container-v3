package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ApplicationContext {
    private final String basePackage;
    // 빈 이름 -> 빈 생성 정보 (스캔으로 모은다)
    private final Map<String, BeanDefinition> beanDefinitions = new LinkedHashMap<>();
    // 빈 이름 -> 싱글톤 인스턴스
    private final Map<String, Object> beans = new LinkedHashMap<>();
    // 생성 중인 빈 이름 (순환 의존 감지용)
    private final Set<String> beansInCreation = new LinkedHashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        Reflections reflections = new Reflections(basePackage);

        scanComponents(reflections);
        scanBeanMethods(reflections);

        for (String beanName : beanDefinitions.keySet()) {
            genBean(beanName);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        Object bean = beans.get(beanName);

        if (bean == null) {
            bean = createBean(beanName);
        }

        return (T) bean;
    }

    // ===== 스캔 =====

    private void scanComponents(Reflections reflections) {
        // @Service, @Repository, @Configuration 처럼 @Component 가 붙은 애너테이션을 단 클래스도 함께 찾는다.
        for (Class<?> clazz : reflections.getTypesAnnotatedWith(Component.class)) {
            if (!isInstantiableClass(clazz)) continue;

            register(new ComponentBeanDefinition(Ut.str.lcfirst(clazz.getSimpleName()), clazz));
        }
    }

    private void scanBeanMethods(Reflections reflections) {
        for (Class<?> configClass : reflections.getTypesAnnotatedWith(Configuration.class)) {
            if (!isInstantiableClass(configClass)) continue;

            String configBeanName = Ut.str.lcfirst(configClass.getSimpleName());

            for (Method method : configClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Bean.class)) continue;

                // @Bean 메서드 이름이 빈 이름이 된다.
                register(new MethodBeanDefinition(method.getName(), configBeanName, method));
            }
        }
    }

    private void register(BeanDefinition definition) {
        BeanDefinition existing = beanDefinitions.putIfAbsent(definition.name(), definition);

        if (existing != null && !existing.equals(definition)) {
            throw new IllegalStateException(
                    "Duplicate bean name '%s': %s, %s".formatted(definition.name(), existing, definition)
            );
        }
    }

    private boolean isInstantiableClass(Class<?> clazz) {
        return !clazz.isAnnotation()
                && !clazz.isInterface()
                && !clazz.isEnum()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    // ===== 생성 =====

    private Object createBean(String beanName) {
        BeanDefinition definition = beanDefinitions.get(beanName);

        if (definition == null) {
            throw new IllegalArgumentException("No bean named '%s'".formatted(beanName));
        }

        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("Circular dependency: %s -> %s".formatted(beansInCreation, beanName));
        }

        try {
            Object bean = definition.create(this);
            beans.put(beanName, bean);

            return bean;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to create bean '%s'".formatted(beanName), e);
        } finally {
            beansInCreation.remove(beanName);
        }
    }

    // 파라미터 하나에 들어갈 빈을 찾아 (필요하면 먼저 만들어) 돌려준다.
    private Object resolveDependency(Parameter parameter) {
        Class<?> type = parameter.getType();

        List<String> candidates = beanDefinitions.values().stream()
                .filter(definition -> type.isAssignableFrom(definition.type()))
                .map(BeanDefinition::name)
                .toList();

        if (candidates.size() == 1) {
            return genBean(candidates.getFirst());
        }

        // 후보가 없거나 여러 개면 이름으로 고른다: 파라미터 이름(-parameters 컴파일 시) → 타입 이름 첫 글자 소문자
        List<String> nameHints = parameter.isNamePresent()
                ? List.of(parameter.getName(), Ut.str.lcfirst(type.getSimpleName()))
                : List.of(Ut.str.lcfirst(type.getSimpleName()));

        for (String name : nameHints) {
            if (candidates.contains(name)) return genBean(name);
        }

        throw new IllegalStateException(
                "Cannot resolve dependency of type %s (candidates: %s)".formatted(type.getName(), candidates)
        );
    }

    private Object[] resolveArguments(Parameter[] parameters) {
        return Arrays.stream(parameters)
                .map(this::resolveDependency)
                .toArray();
    }

    // ===== 빈 생성 정보 =====

    private sealed interface BeanDefinition permits ComponentBeanDefinition, MethodBeanDefinition {
        String name();

        Class<?> type();

        Object create(ApplicationContext context)
                throws InstantiationException, IllegalAccessException, InvocationTargetException;
    }

    // @Component 계열 클래스: 생성자 파라미터로 의존 빈을 주입해 만든다.
    private record ComponentBeanDefinition(String name, Class<?> type) implements BeanDefinition {
        @Override
        public Object create(ApplicationContext context)
                throws InstantiationException, IllegalAccessException, InvocationTargetException {
            // 파라미터가 가장 많은 생성자를 사용한다. (@RequiredArgsConstructor 로 만든 생성자)
            Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();

            constructor.setAccessible(true);

            return constructor.newInstance(context.resolveArguments(constructor.getParameters()));
        }
    }

    // @Configuration 클래스의 @Bean 메서드: 설정 빈에서 메서드를 호출해 반환값을 빈으로 쓴다.
    private record MethodBeanDefinition(String name, String configBeanName, Method method) implements BeanDefinition {
        @Override
        public Class<?> type() {
            return method.getReturnType();
        }

        @Override
        public Object create(ApplicationContext context) throws IllegalAccessException, InvocationTargetException {
            Object configBean = context.genBean(configBeanName);

            method.setAccessible(true);

            return method.invoke(configBean, context.resolveArguments(method.getParameters()));
        }
    }
}
