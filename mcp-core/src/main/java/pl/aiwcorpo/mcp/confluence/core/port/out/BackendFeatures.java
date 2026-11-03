package pl.aiwcorpo.mcp.confluence.core.port.out;

/** What the probed Confluence version allows; the answer of the capability probe, seen from the use cases. */
public interface BackendFeatures {

    /** True when the version is a tested one (or the operator allowed writes on an unknown one). */
    boolean writesAllowed();
}
