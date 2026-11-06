// Changes only inside granted scopes. `principal in resource.scopes` holds when one of the user's groups is mapped to a
// scope of the resource: a space grant (Confluence::Space/<key>) covers the space and its pages, a page grant
// (Confluence::Page/<root>) covers the page tree below the root, because the resolver puts the id of every ancestor page
// into the scopes of a page.

// Creating a page directly in a space is an update of the space.
@id("confluence-update-space-in-scope")
permit (
  principal in UserGroup::"mcp-confluence-write",
  action == Confluence::Action::"update",
  resource is Confluence::Space
)
when { principal in resource.scopes };

@id("confluence-update-page-in-scope")
permit (
  principal in UserGroup::"mcp-confluence-write",
  action == Confluence::Action::"update",
  resource is Confluence::Page
)
when { principal in resource.scopes };

// Deleting a whole space is not offered to agents: there is no permit for delete on Confluence::Space.
@id("confluence-delete-page-in-scope")
permit (
  principal in UserGroup::"mcp-confluence-destructive",
  action == Confluence::Action::"delete",
  resource is Confluence::Page
)
when { principal in resource.scopes };
