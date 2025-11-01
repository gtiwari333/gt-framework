package gt.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DI {

    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final String basePackage;

    public DI(String basePackage) {
        this.basePackage = basePackage;
    }

    public DI(Class mainClass) {
        this.basePackage = mainClass.getPackageName();
    }

    public DI initialize() throws Exception {
        Set<Class<?>> components = findClassesAnnotatedWith(basePackage, Component.class);
        for (Class<?> componentClass : components) {
            if (!instances.containsKey(componentClass)) {
                createAndInject(componentClass);
            }
        }
        return this;
    }

    public static Set<Class<?>> findClassesAnnotatedWith(String packageName, Class<? extends Annotation> annotationClass) {
        Set<Class<?>> annotatedClasses = new HashSet<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String path = packageName.replace('.', '/');
        InputStream stream = classLoader.getResourceAsStream(path);
        if (stream == null) {
            return annotatedClasses; // Package not found or empty
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        reader.lines()
            .filter(line -> line.endsWith(".class"))
            .map(line -> packageName + "." + line.substring(0, line.lastIndexOf('.')))
            .forEach(className -> {
                try {
                    Class<?> clazz = Class.forName(className);
                    if (clazz.isAnnotationPresent(annotationClass)) {
                        annotatedClasses.add(clazz);
                    }
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Warning: Class not found - " + className + ": " + e.getMessage(), e);
                }
            });

        return annotatedClasses;
    }

    private Object createAndInject(Class<?> componentClass) throws Exception {
        if (instances.containsKey(componentClass)) {
            return instances.get(componentClass);
        }

        // Handle constructor injection
        Constructor<?>[] constructors = componentClass.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(Autowired.class)) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                Object[] params = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    params[i] = createAndInject(paramTypes[i]);
                }
                Object instance = constructor.newInstance(params);
                instances.put(componentClass, instance);
                injectFields(instance); // Also inject fields after constructor
                return instance;
            }
        }

        // Default constructor if no @Autowired constructor found
        Object instance = componentClass.getDeclaredConstructor().newInstance();
        instances.put(componentClass, instance);
        injectFields(instance);
        return instance;
    }

    private void injectFields(Object instance) throws Exception {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
                field.setAccessible(true);
                Object dependency = createAndInject(field.getType());
                field.set(instance, dependency);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getComponent(Class<T> type) {
        return (T) instances.get(type);
    }
}
