package com.aewol.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;
import java.util.Properties;

/**
 * Spring Boot 없이 application.yml을 @PropertySource로 로드하기 위한 팩토리.
 */
public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) throws IOException {
        if (!resource.getResource().exists()) {
            throw new FileNotFoundException(
                resource.getResource().getDescription() + " cannot be opened because it does not exist");
        }
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource.getResource());
        Properties properties = Objects.requireNonNull(factory.getObject());
        String sourceName = (name != null && !name.isEmpty())
                ? name
                : resource.getResource().getFilename();
        return new PropertiesPropertySource(sourceName, properties);
    }
}
