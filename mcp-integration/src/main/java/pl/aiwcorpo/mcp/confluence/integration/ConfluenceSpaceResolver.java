package pl.aiwcorpo.mcp.confluence.integration;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.confluence.core.domain.ConfluenceResourceModel;
import pl.aiwcorpo.mcp.confluence.core.domain.Space;
import pl.aiwcorpo.mcp.confluence.core.port.out.ConfluenceResourcePort;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;

/** Tells the starter what a space key is: the space as Confluence reports it, with its labels as the classification. */
@Component
public class ConfluenceSpaceResolver implements ResourceResolver<Space> {

    private final ConfluenceResourcePort resource;

    public ConfluenceSpaceResolver(ConfluenceResourcePort resource) {
        this.resource = resource;
    }

    @Override
    public String resourceType() {
        return ConfluenceResourceModel.SPACE_TYPE;
    }

    @Override
    public Optional<Space> fetch(String spaceKey) {
        return resource.findSpace(spaceKey);
    }

    @Override
    public Map<String, Space> fetchAll(Collection<String> spaceKeys) {
        return resource.findSpaces(spaceKeys);
    }

    @Override
    public ResolvedResource describe(Space space) {
        return ConfluenceResourceDescription.of(space);
    }

    @Override
    public Optional<ResolvedResource> probe(String spaceKey) {
        return ConfluenceResourceDescription.probeOfSpace(spaceKey);
    }
}
