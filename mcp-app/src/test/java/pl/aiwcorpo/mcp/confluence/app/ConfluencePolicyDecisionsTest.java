package pl.aiwcorpo.mcp.confluence.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.Caller;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.test.PolicyDecisions;
import pl.aiwcorpo.mcp.platform.test.PolicyDecisions.Decision;

/**
 * The decisions of the policies in {@code mcp/policies/} on the resources of this server, without Spring and without
 * the gateway: who may read, change and delete which space or page tree, given the scope mapping an administrator would
 * set. The rules for restricted spaces come from the platform, generated from the classification declared for
 * {@code Confluence::Space}.
 */
class ConfluencePolicyDecisionsTest {

    private static final String READ = "mcp-confluence-read";
    private static final String WRITE = "mcp-confluence-write";
    private static final String DESTRUCTIVE = "mcp-confluence-destructive";
    private static final String ENG = "mcp-confluence-space-ENG";
    private static final String HANDBOOK = "mcp-confluence-handbook-editors";

    private final PolicyDecisions policies = PolicyDecisions.fromResources().scopeMapping(Map.of(
            ENG, List.of("Confluence::Space/ENG"),
            HANDBOOK, List.of("Confluence::Page/1000")));

    private static ResolvedResource space(String key, List<String> labels) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("key", key);
        attributes.put("labels", labels);
        return ResolvedResource.of("Confluence::Space", key, attributes).withScopes(List.of("Confluence::Space/" + key));
    }

    /** Scopes as the resolver derives them: the space, every ancestor page (root first) and the page itself. */
    private static ResolvedResource page(String id, String spaceKey, List<String> labels, List<String> ancestors) {
        List<String> scopes = new ArrayList<>();
        scopes.add("Confluence::Space/" + spaceKey);
        ancestors.forEach(ancestor -> scopes.add("Confluence::Page/" + ancestor));
        scopes.add("Confluence::Page/" + id);
        return ResolvedResource.of("Confluence::Page", id, Map.of("id", id, "ancestorIds", ancestors))
                .withParent(space(spaceKey, labels)).withScopes(scopes);
    }

    private static ResolvedResource page(String id, String spaceKey, List<String> ancestors) {
        return page(id, spaceKey, List.of(), ancestors);
    }

    @Test
    void aReaderReadsPagesAndSpacesWithoutAnyScope() {
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", page("501", "ENG", List.of()));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", page("77", "OPS", List.of("70")));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", space("OPS", List.of()));
    }

    @Test
    void aReadIsDecidedByTheReadPolicy() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ), "read", page("501", "ENG", List.of()));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("confluence-read");
    }

    @Test
    void aCallerWithoutTheReadGroupReadsNothing() {
        policies.assertDenied(PolicyDecisions.caller(), "read", page("501", "ENG", List.of()));
        policies.assertDenied(PolicyDecisions.caller(WRITE, ENG), "read", space("ENG", List.of()));
    }

    @Test
    void aPageOfASpaceGrantedToTheWriterIsUpdated() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, ENG), "update", page("501", "ENG", List.of("500")));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("confluence-update-page-in-scope");
    }

    @Test
    void aPageInASpaceWithoutAGrantIsNotUpdated() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, ENG), "update", page("77", "OPS", List.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.determiningPolicies()).isEmpty();
    }

    @Test
    void theGrantWithoutTheWriteGroupChangesNothing() {
        policies.assertDenied(PolicyDecisions.caller(READ, ENG), "update", page("501", "ENG", List.of()));
        policies.assertDenied(PolicyDecisions.caller(READ, ENG), "update", space("ENG", List.of()));
    }

    @Test
    void aPageCreatedDirectlyInAGrantedSpaceIsAnUpdateOfTheSpace() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, ENG), "update", space("ENG", List.of()));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("confluence-update-space-in-scope");
    }

    @Test
    void aPageTreeGrantCoversTheRootAndEveryPageBelowIt() {
        Caller editor = PolicyDecisions.caller(READ, WRITE, HANDBOOK);

        policies.assertAllowed(editor, "update", page("1000", "OPS", List.of()));
        policies.assertAllowed(editor, "update", page("1001", "OPS", List.of("1000")));
        policies.assertAllowed(editor, "update", page("1002", "OPS", List.of("1000", "1001")));
    }

    @Test
    void aPageTreeGrantDoesNotCoverTheSiblingsTheParentOrTheSpace() {
        Caller editor = PolicyDecisions.caller(READ, WRITE, HANDBOOK);

        policies.assertDenied(editor, "update", page("2000", "OPS", List.of()));
        policies.assertDenied(editor, "update", page("2001", "OPS", List.of("2000")));
        policies.assertDenied(editor, "update", page("999", "OPS", List.of()));
        policies.assertDenied(editor, "update", space("OPS", List.of()));
    }

    @Test
    void aPageMovedOutOfTheGrantedTreeIsJudgedByWhereConfluenceSaysItIsNow() {
        Caller editor = PolicyDecisions.caller(READ, WRITE, HANDBOOK);

        policies.assertDenied(editor, "update", page("1001", "OPS", List.of("2000")));
        policies.assertAllowed(editor, "update", page("2001", "OPS", List.of("1000")));
    }

    @Test
    void aPageIsDeletedInTheScopeWithTheDestructiveGroup() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, DESTRUCTIVE, ENG), "delete", page("501", "ENG", List.of()));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("confluence-delete-page-in-scope");
        policies.assertAllowed(PolicyDecisions.caller(READ, DESTRUCTIVE, HANDBOOK), "delete", page("1001", "OPS", List.of("1000")));
    }

    @Test
    void aDeleteNeedsTheDestructiveGroupAndTheScope() {
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, ENG), "delete", page("501", "ENG", List.of()));
        policies.assertDenied(PolicyDecisions.caller(READ, DESTRUCTIVE, ENG), "delete", page("77", "OPS", List.of()));
        policies.assertDenied(PolicyDecisions.caller(READ, DESTRUCTIVE), "delete", page("501", "ENG", List.of()));
    }

    @Test
    void aWholeSpaceIsNeverDeleted() {
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, DESTRUCTIVE, ENG), "delete", space("ENG", List.of()));
    }

    @Test
    void aPageAndASpaceLabelledRestrictedAreDeniedByThePlatformRuleWhatEverTheGroups() {
        Caller everything = PolicyDecisions.caller(READ, WRITE, DESTRUCTIVE, ENG, HANDBOOK);

        for (String action : List.of("read", "update", "delete")) {
            Decision pageDecision = policies.decide(everything, action, page("900", "ENG", List.of("restricted", "security"), List.of("1000")));
            assertThat(pageDecision.allowed()).as("page " + action).isFalse();
            assertThat(pageDecision.determiningPolicies()).as("page " + action).isNotEmpty().allMatch(id -> id.startsWith("platform-"));
        }
        for (String action : List.of("read", "update")) {
            Decision spaceDecision = policies.decide(everything, action, space("SEC", List.of("restricted", "security")));
            assertThat(spaceDecision.allowed()).as("space " + action).isFalse();
            assertThat(spaceDecision.determiningPolicies()).as("space " + action).isNotEmpty().allMatch(id -> id.startsWith("platform-"));
        }
    }

    @Test
    void aPageInARestrictedSpaceIsDeniedWhateverTheLabelsAreOtherwise() {
        ResolvedResource restricted = page("900", "SEC", List.of("restricted"), List.of());

        Decision decision = policies.decide(PolicyDecisions.caller(READ), "read", restricted);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.determiningPolicies()).isNotEmpty().allMatch(id -> id.startsWith("platform-"));
    }

    @Test
    void theLabelsAreNormalizedBeforeThePlatformRuleLooksAtThem() {
        policies.assertDenied(PolicyDecisions.caller(READ), "read", page("1", "ENG", List.of("Restricted"), List.of()));
        policies.assertDenied(PolicyDecisions.caller(READ), "read", page("1", "ENG", List.of("docs", " RESTRICTED "), List.of()));
        policies.assertDenied(PolicyDecisions.caller(READ), "read", space("ENG", List.of("Restricted")));
    }

    @Test
    void aSpaceWithOtherLabelsOrNoneIsNotRestricted() {
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", page("1", "ENG", List.of("docs", "team-x"), List.of()));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", space("ENG", List.of()));
    }

    @Test
    void aSpaceWithoutTheRequiredLabelsIsRefusedBeforeAnyPolicyRuns() {
        ResolvedResource noLabels = ResolvedResource.of("Confluence::Space", "ENG", Map.of("key", "ENG"))
                .withScopes(List.of("Confluence::Space/ENG"));

        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ), "read", noLabels))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIdOutsideTheDeclaredPatternIsRefused() {
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ), "read", page("0123", "ENG", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ), "read", space("~jdoe", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOverrideOfTheAdministratorClosesDeletesEverywhere() {
        PolicyDecisions frozen = policies.override("override-no-delete",
                "forbid (principal, action == Confluence::Action::\"delete\", resource);");

        frozen.assertDenied(PolicyDecisions.caller(READ, DESTRUCTIVE, ENG), "delete", page("501", "ENG", List.of()));
        frozen.assertAllowed(PolicyDecisions.caller(READ, WRITE, ENG), "update", page("501", "ENG", List.of()));
    }
}
