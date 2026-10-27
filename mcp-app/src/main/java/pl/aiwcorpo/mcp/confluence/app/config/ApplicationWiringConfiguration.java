package pl.aiwcorpo.mcp.confluence.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.confluence.application.service.ConfluenceService;
import pl.aiwcorpo.mcp.confluence.core.port.in.ConfluenceUseCase;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;

/** Assembles the hexagon: framework-free use cases from mcp-application, adapters from mcp-integration. */
@Configuration
public class ApplicationWiringConfiguration {

    @Bean
    ConfluenceUseCase confluenceUseCase(ConfluenceResourcePort resource) {
        return new ConfluenceService(resource);
    }
}
