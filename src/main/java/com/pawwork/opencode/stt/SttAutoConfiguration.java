package com.pawwork.opencode.stt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the local speech-to-text engine into the application context.
 *
 * <p>The engine itself is created only when the sherpa-onnx bindings are on the classpath. When
 * they are not, the application still starts and every other feature — chat, the coding tools, the
 * browser recogniser — keeps working; only server-side transcription reports that the engine is
 * unavailable.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(SttProperties.class)
public class SttAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "com.k2fsa.sherpa.onnx.OfflineRecognizer")
    public SttEngine sttEngine(SttProperties properties) {
        SttEngine engine = new SttEngine(properties);
        engine.start();
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SttEngine.class)
    public SttModelManager sttModelManager(SttEngine engine, SttProperties properties) {
        return new SttModelManager(engine, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SttService sttService(SttProperties properties,
                                 ObjectProvider<SttEngine> engine,
                                 ObjectProvider<SttModelManager> models) {
        return new SttService(properties, engine, models);
    }
}
