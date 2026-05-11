package jabaclass.payment.config;

import brave.Tracing;
import brave.handler.SpanHandler;
import brave.sampler.Sampler;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

@Configuration
public class TracingConfig {

    @Value("${management.zipkin.tracing.endpoint:http://localhost:9411/api/v2/spans}")
    private String zipkinEndpoint;

    @Value("${spring.application.name:payment}")
    private String serviceName;

    @Bean(destroyMethod = "close")
    public BytesMessageSender zipkinSender() {
        return URLConnectionSender.create(zipkinEndpoint);
    }

    @Bean(destroyMethod = "close")
    public SpanHandler zipkinSpanHandler(BytesMessageSender sender) {
        return AsyncZipkinSpanHandler.create(sender);
    }

    @Bean(destroyMethod = "close")
    public Tracing braveTracing(SpanHandler spanHandler) {
        return Tracing.newBuilder()
                .localServiceName(serviceName)
                .addSpanHandler(spanHandler)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .build();
    }

    @Bean
    public brave.Tracer braveNativeTracer(Tracing tracing) {
        return tracing.tracer();
    }

    @Bean
    public BraveCurrentTraceContext braveCurrentTraceContext(Tracing tracing) {
        return new BraveCurrentTraceContext(tracing.currentTraceContext());
    }

    @Bean
    public BravePropagator bravePropagator(Tracing tracing) {
        return new BravePropagator(tracing);
    }

    @Bean
    public BraveTracer micrometerTracer(brave.Tracer tracer, BraveCurrentTraceContext currentTraceContext) {
        return new BraveTracer(tracer, currentTraceContext);
    }

    @Bean
    public ObservationRegistryCustomizer<ObservationRegistry> tracingCustomizer(
            BraveTracer tracer,
            BravePropagator propagator) {
        return registry -> registry.observationConfig()
                .observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                        new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                        new DefaultTracingObservationHandler(tracer)
                ));
    }
}